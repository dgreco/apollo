// SPDX-FileCopyrightText: 2026 David Greco <greco@acm.org>
//
// SPDX-License-Identifier: Apache-2.0

package apollo.mcp

import apollo.util.Jx
import apollo.util.Jx.*
import kyo.*
import kyo.Structure.Value

/** One connected MCP server, whatever the transport. The manager registers
  * tools against this surface; stdio and streamable-HTTP implementations
  * differ only in how a JSON-RPC message travels.
  */
trait McpConnection:
  def serverName: String
  def alive: Boolean
  /** True for the stdio transport (a spawned child). A child that dies while
    * a call is in flight may already have applied it, which is what makes a
    * blind retry unsafe there — see `McpServerHandle.request`.
    */
  def stdio: Boolean
  /** The server's `initialize` result (capabilities gate the generated
    * resource/prompt utility tools).
    */
  def initializeResult: Value
  final def capability(name: String): Boolean =
    (initializeResult / "capabilities" / name).nonEmpty
  def request(
      method: String,
      params: Maybe[Value],
      timeout: Maybe[Duration]
  ): Result[String, Value] < (Sync & Async)
  final def request(method: String, params: Maybe[Value]): Result[String, Value] < (Sync & Async) =
    request(method, params, Absent)
  def notify(method: String, params: Maybe[Value]): Unit < (Sync & Async)
  def close: Unit < (Sync & Async)
  /** Effect-free teardown for JVM/Native shutdown hooks. */
  def destroyNow(): Unit
end McpConnection

/** One connected MCP server over the stdio transport: a spawned subprocess
  * speaking newline-delimited JSON-RPC 2.0 on stdin/stdout (stderr is the
  * server's log channel and is drained, kept out of the protocol).
  *
  * Calls are serialized through a mutex — the agent loop executes tool calls
  * sequentially, so a single in-flight request keeps the wiring simple: write
  * the request, then wait for the response promise the reader fiber completes.
  * Server→client requests are answered minimally (`ping` succeeds, anything
  * else gets METHOD_NOT_FOUND); notifications are ignored.
  */
final class McpClient private (
    val serverName: String,
    proc: Process,
    mutex: Meter,
    requestTimeout: Duration
) extends McpConnection:
  private val nextId  = new java.util.concurrent.atomic.AtomicLong(0L)
  private val pending = new java.util.concurrent.ConcurrentHashMap[Long, Promise[Result[String, Value], Any]]()
  @volatile private var deadReason: Maybe[String] = Absent

  @volatile private var initResult: Value = Value.Null
  def initializeResult: Value = initResult

  /** Invoked on `notifications/tools/list_changed` (set by the supervisor
    * to refresh the registration in place).
    */
  @volatile private[mcp] var onToolsListChanged: () => Unit < Sync = () => ()

  /** Handles a server→client request (sampling/createMessage, elicitation/create).
    * Set by the manager; defaults to "not supported" so unwired methods are
    * refused politely. */
  @volatile private[mcp] var onServerRequest: (String, Value) => Result[String, Value] < (Sync & Async) =
    (m, _) => Result.fail(s"method not supported: $m")

  def alive: Boolean = deadReason.isEmpty
  def stdio: Boolean = true

  /** Sends one request and waits for its response (result object), failing
    * with a readable message on JSON-RPC error, timeout, or a dead server.
    */
  def request(
      method: String,
      params: Maybe[Value],
      timeout: Maybe[Duration]
  ): Result[String, Value] < (Sync & Async) =
    deadReason match
      case Present(reason) => Result.fail(s"MCP server '$serverName' is not running: $reason")
      case Absent =>
        Abort.run[Closed](mutex.run(requestLocked(method, params, timeout.getOrElse(requestTimeout)))).map {
          case Result.Success(r) => r
          case _                 => Result.fail(s"MCP server '$serverName' connection is closed")
        }

  private def requestLocked(
      method: String,
      params: Maybe[Value],
      requestTimeout: Duration
  ): Result[String, Value] < (Sync & Async) =
    val id = nextId.incrementAndGet()
    Promise.init[Result[String, Value], Any].map { promise =>
      pending.put(id, promise)
      val msg = Jx.objOf(
        "jsonrpc" -> Present(Jx.str("2.0")),
        "id"      -> Present(Jx.num(id)),
        "method"  -> Present(Jx.str(method)),
        "params"  -> params
      )
      writeLine(Jx.render(msg)) match
        case Result.Failure(err) =>
          pending.remove(id)
          Result.fail(err)
        case _ =>
          Abort.run[Timeout](Async.timeout(requestTimeout)(promise.get)).map {
            case Result.Success(r) => r
            case _ =>
              pending.remove(id)
              Result.fail(s"MCP server '$serverName': $method timed out after ${requestTimeout.show}")
          }
    }
  end requestLocked

  /** Fire-and-forget notification (no id, no response). */
  def notify(method: String, params: Maybe[Value]): Unit < Sync =
    Sync.defer {
      writeLine(Jx.render(Jx.objOf(
        "jsonrpc" -> Present(Jx.str("2.0")),
        "method"  -> Present(Jx.str(method)),
        "params"  -> params
      )))
      ()
    }

  private def writeLine(json: String): Result[String, Unit] =
    import AllowUnsafe.embrace.danger
    try
      val out = proc.unsafe.stdinJava
      out.synchronized {
        out.write((json + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8))
        out.flush()
      }
      Result.succeed(())
    catch case e: java.io.IOException => Result.fail(s"MCP server '$serverName': write failed (${e.getMessage})")

  /** Write a JSON-RPC success reply to a server→client request. */
  private def replyResult(id: Value, result: Value): Unit =
    writeLine(Jx.render(Jx.obj("jsonrpc" -> Jx.str("2.0"), "id" -> id, "result" -> result))); ()
  /** Write a JSON-RPC error reply to a server→client request. */
  private def replyError(id: Value, code: Long, message: String): Unit =
    writeLine(Jx.render(Jx.obj("jsonrpc" -> Jx.str("2.0"), "id" -> id,
      "error" -> Jx.obj("code" -> Jx.num(code), "message" -> Jx.str(message))))); ()

  // --- inbound ------------------------------------------------------------

  private[mcp] def handleLine(line: String): Unit < Sync =
    if line.isBlank then Sync.defer(())
    else
      Jx.parse(line) match
        case Result.Success(msg) =>
          (msg / "id").asLong match
            case Present(id) if (msg / "method").asStr.isEmpty =>
              // A response to one of our requests.
              Maybe(pending.remove(id)) match
                case Present(promise) =>
                  val outcome = msg / "error" match
                    case Present(err) =>
                      val code    = (err / "code").asLong.getOrElse(0L)
                      val message = (err / "message").asStr.getOrElse("unknown error")
                      Result.fail(s"MCP server '$serverName' error $code: $message")
                    case Absent =>
                      Result.succeed((msg / "result").getOrElse(Jx.obj()))
                  promise.completeDiscard(Result.succeed(outcome))
                case Absent => Sync.defer(()) // late reply after timeout: drop
            case _ =>
              (msg / "method").asStr match
                case Present("ping") =>
                  // Server-initiated request we can honor.
                  (msg / "id") match
                    case Present(id) =>
                      Sync.defer(writeLine(Jx.render(Jx.obj(
                        "jsonrpc" -> Jx.str("2.0"), "id" -> id, "result" -> Jx.obj()
                      )))).unit
                    case Absent => Sync.defer(())
                case Present(other) =>
                  (msg / "id") match
                    case Present(id) =>
                      if other == "sampling/createMessage" || other == "elicitation/create" then
                        // Handle asynchronously (a sampling call runs the model) so the
                        // read loop keeps draining; write the JSON-RPC reply when done.
                        val params = (msg / "params").getOrElse(Jx.obj())
                        Fiber.initUnscoped(
                          onServerRequest(other, params).map {
                            case Result.Success(res) => Sync.defer(replyResult(id, res))
                            case Result.Failure(err) => Sync.defer(replyError(id, -32603L, err))
                          }
                        ).unit
                      else
                        // Unsupported server→client request: refuse politely.
                        Sync.defer(replyError(id, -32601L, s"method not supported: $other")).unit
                    case Absent =>
                      if other == "notifications/tools/list_changed" then onToolsListChanged()
                      else Sync.defer(())
                case Absent => Sync.defer(())
        case _ => Sync.defer(()) // non-JSON stdout noise: ignored
  end handleLine

  private[mcp] def markDead(reason: String): Unit < Sync =
    Sync.defer {
      deadReason = Present(reason)
    }.andThen {
      import scala.jdk.CollectionConverters.*
      Kyo.foreachDiscard(pending.values.asScala.toList) { p =>
        p.completeDiscard(Result.succeed(Result.fail(s"MCP server '$serverName' exited: $reason")))
      }.andThen(Sync.defer(pending.clear()))
    }

  /** Terminates the server process (SIGTERM, then SIGKILL after a grace
    * period — the upstream kill sequence).
    */
  def close: Unit < (Sync & Async) =
    Sync.defer { deadReason = Present("closed") }
      .andThen(proc.destroy)
      .andThen(proc.waitFor(2.seconds))
      .map {
        case Present(_) => ()
        case Absent     => proc.destroyForcibly
      }

  /** Effect-free kill for JVM/Native shutdown hooks. */
  def destroyNow(): Unit =
    import AllowUnsafe.embrace.danger
    deadReason = Present("shutdown")
    proc.unsafe.destroy()
    Thread.sleep(200)
    if proc.unsafe.isAlive() then proc.unsafe.destroyForcibly()
end McpClient

object McpClient:

  /** Spawns an MCP server subprocess and completes the `initialize`
    * handshake. The child inherits the parent environment with the
    * configured overlay applied (secrets referenced as `${VAR}` are expanded
    * by the config layer before reaching here).
    */
  def connect(
      serverName: String,
      argv: List[String],
      env: Map[String, String],
      cwd: Maybe[String],
      requestTimeout: Duration,
      clientVersion: String,
      stderrFile: Maybe[java.nio.file.Path] = Absent,
      replaceEnv: Boolean = false
  ): Result[String, McpClient] < (Sync & Async) =
    val base = Command(argv*)
    val withCwd = cwd match
      case Present(dir) => base.cwd(Path(dir))
      case Absent       => base
    val withEnv = if replaceEnv then withCwd.envReplace(env) else withCwd.envAppend(env)
    val cmd = stderrFile match
      case Present(f) => withEnv.pipeStdin.stderrToFile(Path(f.toString), append = true)
      case Absent     => withEnv.pipeStdin
    Abort.run[CommandException](cmd.spawnUnscoped).map {
      case Result.Failure(e) =>
        Result.fail(s"MCP server '$serverName': failed to launch ${argv.mkString(" ")} (${e.getMessage})")
      case Result.Panic(e) =>
        Result.fail(s"MCP server '$serverName': failed to launch (${e.getMessage})")
      case Result.Success(proc) =>
        Meter.initMutexUnscoped.map { mutex =>
          val client = new McpClient(serverName, proc, mutex, requestTimeout)
          startPumps(client, proc, drainStderr = stderrFile.isEmpty)
            .andThen(handshake(client, clientVersion))
        }
    }
  end connect

  /** Reader fiber (stdout → lines → handleLine), stderr drain (only when it
    * wasn't redirected to the log file), and the exit watcher that fails
    * everything pending when the server dies.
    */
  private def startPumps(client: McpClient, proc: Process, drainStderr: Boolean): Unit < Sync =
    val reader =
      Scope.run {
        proc.stdout.fold(new java.io.ByteArrayOutputStream()) { (buf, b) =>
          if b == '\n'.toByte then
            val line = buf.toString(java.nio.charset.StandardCharsets.UTF_8)
            buf.reset()
            client.handleLine(line).andThen(buf)
          else
            buf.write(b.toInt)
            buf
        }
      }.unit
    val exitWatch =
      proc.waitFor.map(code => client.markDead(s"exit code ${code.toInt}"))
    Fiber.initUnscoped(reader).unit
      .andThen {
        if drainStderr then
          Fiber.initUnscoped(Scope.run(proc.stderr.fold(())((_, _) => ())).unit).unit
        else ()
      }
      .andThen(Fiber.initUnscoped(exitWatch).unit)
  end startPumps

  private def handshake(
      client: McpClient,
      clientVersion: String
  ): Result[String, McpClient] < (Sync & Async) =
    client.request("initialize", Present(Mcp.initializeParams(clientVersion))).map {
      case Result.Failure(err) =>
        client.close.andThen(Result.fail(err))
      case Result.Success(init) =>
        client.initResult = init
        client.notify("notifications/initialized", Absent)
          .andThen(Result.succeed(client))
      case Result.Panic(e) =>
        client.close.andThen(Result.fail(String.valueOf(e.getMessage)))
    }
end McpClient

/** Protocol constants and shared handshake shapes. */
object Mcp:
  val protocolVersion = "2025-06-18"
  val clientName      = "apollo"

  def initializeParams(clientVersion: String): Value =
    Jx.obj(
      "protocolVersion" -> Jx.str(protocolVersion),
      "capabilities"    -> Jx.obj(),
      "clientInfo" -> Jx.obj(
        "name"    -> Jx.str(clientName),
        "version" -> Jx.str(clientVersion)
      )
    )

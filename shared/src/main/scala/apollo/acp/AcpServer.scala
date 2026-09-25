// SPDX-FileCopyrightText: 2026 David Greco <greco@acm.org>
//
// SPDX-License-Identifier: Apache-2.0

package apollo.acp

import apollo.agent.TurnCallbacks
import apollo.config.{ApolloConfig, ApolloPaths}
import apollo.gateway.SessionHub
import apollo.provider.*
import apollo.util.Jx
import kyo.*

/** ACP server (`apollo acp`): a JSON-RPC-over-stdio agent an editor (Zed /
  * VS Code / JetBrains, any ACP host) connects to. stdout is reserved for
  * protocol frames; logs go to stderr. Agent turns run through the shared
  * `SessionHub`, streaming assistant text as `session/update` notifications.
  *
  * Implemented: initialize, authenticate, session/new, session/prompt (with
  * streaming). session/cancel is acknowledged but does not interrupt a
  * mid-flight turn (a known limitation). */
object AcpServer:

  def run(config: ApolloConfig, paths: ApolloPaths): Unit < (Sync & Async & Scope) =
    Abort.run[ResolveError](Runtime.resolve(config, RuntimeOverrides())).map {
      case Result.Failure(err) => Console.printLineErr(s"acp: ${err.message}")
      case Result.Panic(e)     => Console.printLineErr(s"acp: ${e.getMessage}")
      case Result.Success(runtime) =>
        val hub = new SessionHub(config, paths, runtime)
        Console.printLineErr("acp: ready (JSON-RPC over stdio)").andThen(loop(hub))
    }

  private def send(line: String): Unit < Sync =
    Sync.defer {
      val out = java.lang.System.out
      out.synchronized { out.write((line + "\n").getBytes("UTF-8")); out.flush() }
    }

  private def loop(hub: SessionHub): Unit < (Sync & Async) =
    Sync.defer(Option(readLineBlocking())).map {
      case None       => () // EOF → editor closed the connection
      case Some(line) =>
        val step = if line.trim.isEmpty then Sync.defer(()) else handleLine(line, hub, send)
        step.andThen(loop(hub))
    }

  private val stdin = new java.io.BufferedReader(new java.io.InputStreamReader(java.lang.System.in, "UTF-8"))
  private def readLineBlocking(): String | Null = stdin.readLine()

  /** Parse + dispatch one JSON-RPC line, emitting responses/updates via `send`.
    * Pure enough to unit-test: `send` is the only side channel. */
  def handleLine(line: String, hub: SessionHub, send: String => Unit < Sync): Unit < (Sync & Async) =
    Acp.parse(line) match
      case None      => Sync.defer(())
      case Some(req) => dispatch(req, hub, send)

  private def dispatch(req: Acp.Req, hub: SessionHub, send: String => Unit < Sync): Unit < (Sync & Async) =
    def reply(v: kyo.Structure.Value): Unit < Sync =
      req.id match { case Some(id) => send(Acp.result(id, v)); case None => Sync.defer(()) }
    req.method match
      case "initialize"   => reply(Acp.initializeResult)
      case "authenticate" => reply(Jx.obj())
      case "session/new" =>
        Sync.defer(java.util.UUID.randomUUID.toString).map(sid => reply(Acp.newSessionResult(sid)))
      case "session/load" => reply(Jx.obj())
      case "session/cancel" => Sync.defer(()) // acknowledged; no mid-turn interrupt yet
      case "session/prompt" =>
        Acp.sessionId(req.params) match
          case Absent => req.id match { case Some(id) => send(Acp.error(id, -32602, "missing sessionId")); case None => Sync.defer(()) }
          case Present(sid) =>
            val text = Acp.promptText(req.params)
            val key  = hub.sessionKey("acp", "dm", sid, Absent)
            val streamed = new java.util.concurrent.atomic.AtomicBoolean(false)
            val callbacks = TurnCallbacks(onTextDelta = t => { streamed.set(true); send(Acp.messageChunk(sid, t)) })
            hub.turn(key, "acp", text, callbacks).map { finalText =>
              // Non-streaming providers emit no deltas — deliver the final text as one chunk.
              val emitFinal =
                if !streamed.get && finalText.nonEmpty then send(Acp.messageChunk(sid, finalText))
                else Sync.defer(())
              emitFinal.andThen(req.id match {
                case Some(id) => send(Acp.result(id, Acp.promptResult("end_turn")))
                case None     => Sync.defer(())
              })
            }
      case other =>
        req.id match { case Some(id) => send(Acp.error(id, -32601, s"method not found: $other")); case None => Sync.defer(()) }
end AcpServer

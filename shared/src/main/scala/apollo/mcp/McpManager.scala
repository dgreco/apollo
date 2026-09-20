package apollo.mcp

import apollo.config.{ApolloConfig, ApolloPaths, Fs}
import apollo.tools.{ApprovalDecision, ToolEntry, ToolOutcome, ToolRegistry}
import apollo.util.Jx
import apollo.util.Jx.*
import kyo.*

/** MCP server lifecycle and registration: spawns the stdio servers from
  * `mcp_servers:` concurrently at startup, lists their tools, and registers
  * them into the dynamic tool registry under per-server `mcp-<name>`
  * toolsets with upstream naming (`mcp__<server>__<tool>`) and collision
  * rules. Servers finishing after the discovery bound still register — their
  * tools reach sessions assembled later.
  */
object McpManager:

  enum State:
    case Connecting
    case Connected(toolCount: Int)
    case Reconnecting
    case Failed(reason: String)
    case Parked(reason: String)
    case Unsupported(reason: String)

  import McpConnectError.*

  /** Reliability constants; tests inject tiny cooldowns/intervals. */
  @volatile private[mcp] var tuning: McpTuning = McpTuning()

  final case class ServerStatus(config: McpServerConfig, state: State, client: Maybe[McpConnection])

  private val servers  = new java.util.concurrent.ConcurrentHashMap[String, ServerStatus]()
  private val started  = new java.util.concurrent.atomic.AtomicBoolean(false)
  private val hooked   = new java.util.concurrent.atomic.AtomicBoolean(false)
  /** Session-scoped grants for `trust: untrusted` servers. */
  private val trustGrants = new java.util.concurrent.ConcurrentHashMap[String, Boolean]()

  // Handlers for server→client requests, wired by the CLI layer (a model call
  // for sampling; the UI for elicitation). Absent → the method is unsupported.
  import kyo.Structure.Value
  @volatile private var samplingHandler:    Maybe[Value => Result[String, Value] < (Sync & Async)] = Absent
  @volatile private var elicitationHandler: Maybe[Value => Result[String, Value] < (Sync & Async)] = Absent
  def setSamplingHandler(f: Value => Result[String, Value] < (Sync & Async)): Unit    = samplingHandler = Present(f)
  def setElicitationHandler(f: Value => Result[String, Value] < (Sync & Async)): Unit = elicitationHandler = Present(f)

  /** Routes a server→client request to the wired handler. */
  private[mcp] def dispatchServerRequest(method: String, params: Value): Result[String, Value] < (Sync & Async) =
    method match
      case "sampling/createMessage" =>
        samplingHandler match
          case Present(f) => f(params)
          case Absent     => Result.fail("sampling/createMessage not supported (no handler wired)")
      case "elicitation/create" =>
        elicitationHandler match
          case Present(f) => f(params)
          case Absent     => Result.fail("elicitation/create not supported (no handler wired)")
      case other => Result.fail(s"method not supported: $other")

  def status: List[ServerStatus] =
    import scala.jdk.CollectionConverters.*
    servers.values.asScala.toList.sortBy(_.config.name)

  /** Starts every enabled server (bounded concurrent connects), waiting up
    * to the discovery bound before returning so session assembly sees the
    * tools. `allowlist` mirrors the upstream `-t` behavior: when present,
    * only servers it names (as `<name>` or `mcp-<name>`) are spawned at all.
    * Idempotent — the gateway and its embedded cron scheduler share one
    * start.
    */
  def start(
      config: ApolloConfig,
      paths: ApolloPaths,
      workspace: java.nio.file.Path,
      allowlist: Maybe[List[String]],
      clientVersion: String
  ): Unit < (Sync & Async) =
    if !started.compareAndSet(false, true) then ()
    else
      val (all, warnings) = McpConfig.load(config, workspace)
      val selected = allowlist match
        case Absent => all
        case Present(names) =>
          val wanted = names.toSet
          all.filter(c => wanted.contains(c.name) || wanted.contains(s"mcp-${c.name}"))
      Kyo.foreachDiscard(warnings)(w => Console.printLineErr(s"warning: $w")).andThen {
        if selected.isEmpty then ()
        else
          installShutdownHook()
          val bound = McpConfig.duration(McpConfig.discoveryTimeoutSeconds(config))
          Kyo.foreach(selected) { cfg =>
            Fiber.initUnscoped(connectOne(cfg, config, paths, clientVersion))
          }.map { fibers =>
            Abort.run[Timeout](Async.timeout(bound)(Kyo.foreachDiscard(fibers)(_.get))).unit
          }
      }
  end start

  /** Closes every server and clears the registrations (used by `apollo mcp`
    * probes and tests; normal shutdown goes through the JVM hook).
    */
  def stopAll: Unit < (Sync & Async) =
    import scala.jdk.CollectionConverters.*
    val entries = servers.values.asScala.toList
    Kyo.foreachDiscard(entries) { s =>
      ToolRegistry.unregisterDynamic(s"mcp-${s.config.name}")
      s.client.map(_.close).getOrElse(())
    }.andThen(Sync.defer {
      servers.clear()
      trustGrants.clear()
      started.set(false)
    })

  /** Test hook: clear all process-global state so suites don't leak into each
    * other (mirrors Slack/Discord.resetState). `start` is guarded by `started`,
    * so a leaked `started=true` silently no-ops a later start — reset avoids that.
    * Does not close live clients (tests use short-lived mocks). */
  def resetState(): Unit =
    import scala.jdk.CollectionConverters.*
    servers.values.asScala.foreach(s => ToolRegistry.unregisterDynamic(s"mcp-${s.config.name}"))
    servers.clear()
    trustGrants.clear()
    started.set(false)

  private def installShutdownHook(): Unit =
    if hooked.compareAndSet(false, true) then
      java.lang.Runtime.getRuntime.addShutdownHook(new Thread(() =>
        servers.values.forEach(s => s.client.foreach(_.destroyNow()))
      ))

  // --- per-server connect --------------------------------------------------

  private def connectOne(
      cfg: McpServerConfig,
      config: ApolloConfig,
      paths: ApolloPaths,
      clientVersion: String
  ): Unit < (Sync & Async) =
    Sync.defer {
      lazy val handle: McpServerHandle = new McpServerHandle(
        cfg = cfg,
        tuning = tuning,
        openConn = () =>
          openConnection(cfg, config, paths, clientVersion, stderrLog = true).map { r =>
            r match
              case Result.Success(stdio: McpClient) =>
                // Live registry refresh on tools/list_changed notifications.
                stdio.onToolsListChanged = () => handle.refreshTools()
                // Server→client requests (sampling/elicitation) route to the wired handlers.
                stdio.onServerRequest = (m, p) => dispatchServerRequest(m, p)
              case Result.Success(http: McpHttpClient) =>
                // Same server→client routing over the streamable-HTTP transport.
                http.onServerRequest = (m, p) => dispatchServerRequest(m, p)
              case _ => ()
            r
          },
        listAndRegister = conn =>
          listAllTools(conn, cfg).map {
            case Result.Success(tools) =>
              val entries = buildEntries(cfg, handle, conn, tools)
              ToolRegistry.registerDynamic(s"mcp-${cfg.name}", entries)
              (Result.succeed(entries.length): Result[String, Int])
            case Result.Failure(err) => Result.fail(err)
            case Result.Panic(e)     => Result.fail(String.valueOf(e.getMessage))
          },
        onState = st => { servers.put(cfg.name, ServerStatus(cfg, st, Present(handle))); () }
      )
      handle
    }.map(_.start())
  end connectOne

  /** Transport dispatch shared by startup and `apollo mcp test`: stdio spawn
    * or streamable-HTTP connect, with the upstream-parity refusals for the
    * legacy SSE transport and OAuth.
    */
  private def openConnection(
      cfg: McpServerConfig,
      config: ApolloConfig,
      paths: ApolloPaths,
      clientVersion: String,
      stderrLog: Boolean
  ): Result[McpConnectError, McpConnection] < (Sync & Async) =
    def lift[C <: McpConnection](r: Result[String, C]): Result[McpConnectError, McpConnection] =
      r match
        case Result.Success(c) => Result.succeed(c)
        case Result.Failure(e) => Result.fail(McpConnectFailed(e))
        case Result.Panic(e)   => Result.fail(McpConnectFailed(String.valueOf(e.getMessage)))
    cfg.url match
      case Present(url) =>
        if cfg.transport.contains("sse") then
          Result.fail(McpUnsupported(
            "legacy SSE transport (transport: sse) is not implemented; use a streamable-http endpoint"))
        else
          val timeout = McpConfig.duration(cfg.connectTimeoutSeconds)
          if cfg.usesOAuth then
            val store = new McpOAuthStore(paths)
            store.loadTokens(cfg.name).map {
              case Absent =>
                // No cached grant: park with a login hint rather than hammering
                // the server unauthenticated.
                Result.fail(McpConnectFailed(
                  s"MCP server '${cfg.name}' is not authorized. Run `apollo mcp login ${cfg.name}`."))
              case Present(_) =>
                McpOAuth.tokenSource(cfg, store, timeout).map { ts =>
                  McpHttpClient.connect(cfg.name, url, cfg.headers, timeout, clientVersion, Present(ts)).map(lift)
                }
            }
          else
            McpHttpClient.connect(cfg.name, url, cfg.headers, timeout, clientVersion, Absent).map(lift)
      case Absent =>
        cfg.command match
          case Absent =>
            Result.fail(McpConnectFailed(s"MCP server '${cfg.name}' has no 'command' in config"))
          case Present(command) =>
            val connectV: Result[String, McpClient] < (Sync & Async) =
              if stderrLog then
                val log = paths.logsDir.resolve("mcp-stderr.log")
                val header =
                  // Instant-based stamp (UTC): java.time formatters are not a
                  // safe bet on Native. Same shape as the upstream header.
                  val now = java.time.Instant.now().toString.take(19).replace('T', ' ')
                  s"\n===== [$now] starting MCP server '${cfg.name}' =====\n"
                Fs.createDirs(paths.logsDir)
                  .andThen(Fs.appendString(log, header))
                  .andThen(spawnStdio(cfg, config, command, clientVersion, Present(log)))
              else spawnStdio(cfg, config, command, clientVersion, Absent)
            connectV.map(lift)
  end openConnection

  private def spawnStdio(
      cfg: McpServerConfig,
      config: ApolloConfig,
      command: String,
      clientVersion: String,
      stderrFile: Maybe[java.nio.file.Path]
  ): Result[String, McpClient] < (Sync & Async) =
    McpClient.connect(
      serverName = cfg.name,
      argv = command :: cfg.args,
      env = McpConfig.safeEnv(config.env.resolved, cfg.env),
      cwd = cfg.cwd,
      requestTimeout = McpConfig.duration(cfg.connectTimeoutSeconds),
      clientVersion = clientVersion,
      stderrFile = stderrFile,
      replaceEnv = true
    )

  /** `tools/list` with cursor pagination, hard-capped at the upstream 50
    * pages.
    */
  private def listAllTools(
      client: McpConnection,
      cfg: McpServerConfig
  ): Result[String, List[Value]] < (Sync & Async) =
    def loop(cursor: Maybe[String], acc: List[Value], pages: Int): Result[String, List[Value]] < (Sync & Async) =
      if pages >= 50 then Result.succeed(acc)
      else
        val params = cursor.map(c => Jx.obj("cursor" -> Jx.str(c)))
        client.request("tools/list", params, Present(McpConfig.duration(cfg.connectTimeoutSeconds))).map {
          case Result.Success(result) =>
            val page = result.field("tools").asArr.getOrElse(Chunk.empty).toList
            result.field("nextCursor").asStr.orElse(result.field("next_cursor").asStr) match
              case Present(next) => loop(Present(next), acc ++ page, pages + 1)
              case Absent        => Result.succeed(acc ++ page)
          case Result.Failure(err) => Result.fail(err)
          case Result.Panic(e)     => Result.fail(String.valueOf(e.getMessage))
        }
    loop(Absent, Nil, 0)

  // --- registration --------------------------------------------------------

  /** Builds the server's tool entries: natives filtered by include/exclude,
    * then the capability-gated utility tools, with the upstream collision
    * rules — native beats utility, multi-origin collisions are skipped
    * entirely (fail closed), and names owned elsewhere in the registry are
    * left with their owner.
    */
  private def buildEntries(
      cfg: McpServerConfig,
      handle: McpServerHandle,
      conn: McpConnection,
      tools: List[Value]
  ): List[ToolEntry] =
    val natives = tools.flatMap { tool =>
      (tool / "name").asStr.toList.filter(cfg.toolAllowed).map { raw =>
        val desc = (tool / "description").asStr.filter(_.nonEmpty)
          .getOrElse(McpSchema.defaultDescription(raw, cfg.name))
        val schema = tool.field("inputSchema").orElse(tool.field("input_schema"))
        nativeEntry(cfg, handle, raw, desc, McpSchema.normalizeInputSchema(schema))
      }
    }
    val nativeNames = natives.map(_.name).toSet
    // Capabilities come from the fresh connection: the handle only exposes
    // them once the connection is installed as current, which happens after
    // registration.
    val utilities =
      (if cfg.resourceTools && conn.capability("resources") then McpSchema.resourceUtilities else Nil)
        .concat(if cfg.promptTools && conn.capability("prompts") then McpSchema.promptUtilities else Nil)
        .map(u => utilityEntry(cfg, handle, u))
        .filterNot(u => nativeNames.contains(u.name)) // native wins over utility
    // Multi-origin collisions within the server (e.g. `read-file` and
    // `read_file` both sanitizing to one name) fail closed: all dropped.
    val byGenerated = natives.groupBy(_.name)
    val deduped = natives.filter(e => byGenerated(e.name).length == 1)
    (deduped ++ utilities).filterNot(e => ToolRegistry.nameTaken(e.name))
  end buildEntries

  private def nativeEntry(
      cfg: McpServerConfig,
      handle: McpServerHandle,
      raw: String,
      description: String,
      schema: Value
  ): ToolEntry =
    ToolEntry(
      name = McpSchema.prefixedName(cfg.name, raw),
      toolset = s"mcp-${cfg.name}",
      description = description,
      parametersJson = Jx.render(schema),
      emoji = "🔌",
      maxResultChars = 50000,
      // Registered tools stay visible while the transport recovers; the
      // handle answers with actionable guidance instead of vanishing.
      available = _ => true,
      handler = (args, ctx) =>
        trustGate(cfg, raw, ctx).map {
          case Result.Failure(denial) => ToolOutcome.Error(denial)
          case _ =>
            val params = Jx.obj("name" -> Jx.str(raw), "arguments" -> args)
            handle.request("tools/call", Present(params), Present(McpConfig.duration(cfg.toolTimeoutSeconds))).map {
              case Result.Success(result) =>
                val outcome = McpContent.renderCallResult(cfg.name, result, mediaDir(ctx.paths))
                outcome match
                  case ToolOutcome.Error(_) => handle.recordIsErrorStrike() // isError counts (upstream)
                  case _                    => ()
                outcome
              case Result.Failure(err) => ToolOutcome.Error(McpContent.redact(err))
              case Result.Panic(e) =>
                ToolOutcome.Error(McpContent.redact(s"MCP call failed: ${e.getClass.getSimpleName}: ${e.getMessage}"))
            }
        }
    )

  /** The `trust: untrusted` gate: every call on an untrusted server needs a
    * human grant (session-scoped once given); unattended surfaces auto-deny
    * through their ToolUi exactly like command approvals.
    */
  private def trustGate(
      cfg: McpServerConfig,
      raw: String,
      ctx: apollo.tools.ToolContext
  ): Result[String, Unit] < (Sync & Async) =
    if !cfg.untrusted || trustGrants.containsKey(cfg.name) then Result.succeed(())
    else
      ctx.ui.requestApproval(
        s"MCP server '${cfg.name}' is marked untrusted. Allow tool '$raw'?"
      ).map {
        case ApprovalDecision.Deny =>
          Result.fail(s"MCP tool call denied: server '${cfg.name}' is untrusted and the call was not approved.")
        case ApprovalDecision.Once => Result.succeed(())
        case _ =>
          trustGrants.put(cfg.name, true)
          Result.succeed(())
      }

  private def mediaDir(paths: ApolloPaths): java.nio.file.Path =
    paths.home.resolve("scala-state").resolve("mcp-media")

  // --- utility tools -------------------------------------------------------

  private def utilityEntry(cfg: McpServerConfig, handle: McpServerHandle, spec: McpSchema.UtilitySpec): ToolEntry =
    ToolEntry(
      name = McpSchema.prefixedName(cfg.name, spec.suffix),
      toolset = s"mcp-${cfg.name}",
      description = spec.description(cfg.name),
      parametersJson = spec.parametersJson,
      emoji = "🔌",
      maxResultChars = 50000,
      available = _ => true,
      handler = (args, ctx) => utilityHandler(cfg, handle, spec.suffix, args, ctx)
    )

  private def utilityHandler(
      cfg: McpServerConfig,
      client: McpConnection,
      suffix: String,
      args: Value,
      ctx: apollo.tools.ToolContext
  ): ToolOutcome < (Sync & Async) =
    val timeout = Present(McpConfig.duration(cfg.toolTimeoutSeconds))
    def forward(method: String, params: Maybe[Value], key: String): ToolOutcome < (Sync & Async) =
      client.request(method, params, timeout).map {
        case Result.Success(result) =>
          val items = result.field(key).getOrElse(Jx.arr())
          ToolOutcome.Ok(Jx.render(Jx.obj(key -> items)))
        case Result.Failure(err) => ToolOutcome.Error(McpContent.redact(err))
        case Result.Panic(e)     => ToolOutcome.Error(McpContent.redact(String.valueOf(e.getMessage)))
      }
    suffix match
      case "list_resources" => forward("resources/list", Absent, "resources")
      case "list_prompts"   => forward("prompts/list", Absent, "prompts")
      case "read_resource" =>
        (args / "uri").asStr match
          case Absent => ToolOutcome.Error("Missing required parameter 'uri'")
          case Present(uri) =>
            client.request("resources/read", Present(Jx.obj("uri" -> Jx.str(uri))), timeout).map {
              case Result.Success(result) =>
                val contents = result.field("contents").asArr.getOrElse(Chunk.empty)
                val rendered = Jx.obj("content" -> Value.Sequence(contents.map(c =>
                  Jx.obj("type" -> Jx.str("resource"), "resource" -> c)
                )))
                McpContent.renderCallResult(cfg.name, rendered, mediaDir(ctx.paths))
              case Result.Failure(err) => ToolOutcome.Error(McpContent.redact(err))
              case Result.Panic(e)     => ToolOutcome.Error(McpContent.redact(String.valueOf(e.getMessage)))
            }
      case "get_prompt" =>
        (args / "name").asStr match
          case Absent => ToolOutcome.Error("Missing required parameter 'name'")
          case Present(name) =>
            val params = Jx.objOf(
              "name"      -> Present(Jx.str(name)),
              "arguments" -> args.field("arguments")
            )
            client.request("prompts/get", Present(params), timeout).map {
              case Result.Success(result) =>
                val payload = Jx.objOf(
                  "messages"    -> Present(result.field("messages").getOrElse(Jx.arr())),
                  "description" -> result.field("description")
                )
                ToolOutcome.Ok(Jx.render(payload))
              case Result.Failure(err) => ToolOutcome.Error(McpContent.redact(err))
              case Result.Panic(e)     => ToolOutcome.Error(McpContent.redact(String.valueOf(e.getMessage)))
            }
      case other => ToolOutcome.Error(s"unknown MCP utility: $other")
  end utilityHandler

  // --- probes (apollo mcp test) -------------------------------------------

  /** One-shot connect + tools/list + close, for `apollo mcp test <name>`. */
  def probe(
      cfg: McpServerConfig,
      config: ApolloConfig,
      paths: ApolloPaths,
      clientVersion: String
  ): Result[String, List[(String, String)]] < (Sync & Async) =
    openConnection(cfg, config, paths, clientVersion, stderrLog = false).map {
      case Result.Failure(McpUnsupported(reason)) => Result.fail(reason)
      case Result.Failure(McpConnectFailed(err))  => Result.fail(err)
      case Result.Panic(e)                        => Result.fail(String.valueOf(e.getMessage))
      case Result.Success(client) =>
        listAllTools(client, cfg).map { tools =>
          client.close.andThen(tools.map(list =>
            list.flatMap(t =>
              (t / "name").asStr.toList.map(n => n -> (t / "description").asStr.getOrElse(""))
            )
          ))
        }
    }
end McpManager

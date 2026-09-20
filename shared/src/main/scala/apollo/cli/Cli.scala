package apollo.cli

import apollo.agent.{Agent, SystemPrompt, TurnCallbacks}
import apollo.config.*
import apollo.core.{Message, Content, Role}
import apollo.http.HttpError
import apollo.util.Jx.*
import apollo.util.Style
import kyo.Structure.Value
import apollo.provider.*
import apollo.session.{SessionMeta, SessionStore}
import apollo.skills.SkillStore
import apollo.tools.*
import java.nio.file.Paths
import kyo.*

/** Command dispatch and session assembly: the equivalent of the upstream harness's
  * upstream `main.py`. Bare `apollo` starts the chat REPL; `-z` runs one
  * shot; subcommands cover model/config/sessions/skills/cron/gateway/setup/
  * status/tools.
  */
object Cli:

  def run(argv: List[String]): Unit < (Sync & Async & Scope) =
    val args = CliArgs.parse(argv)
    if args.version then Console.printLine(s"apollo ${BuildInfo.version}")
    else if args.help then Console.printLine(helpText)
    else
      // Home resolution with the upstream profile semantics: -p flag >
      // profile-shaped APOLLO_HOME > sticky <root>/active_profile > default.
      ApolloPaths.resolve(args.profile).map {
        case Result.Failure(err) =>
          Console.printLineErr(Style.red(s"Error: $err")).andThen(Sync.defer {
            java.lang.System.exit(1)
            ()
          })
        case Result.Panic(e) =>
          Console.printLineErr(Style.red(s"Error: ${e.getMessage}"))
        case Result.Success(paths) =>
          ApolloConfig.load(paths, args.ignoreUserConfig).map { (config, configError) =>
            configError match
              case Present(err) if args.oneshot.nonEmpty =>
                // require_parseable_user_config: a one-shot run refuses to
                // start against an invalid config.yaml — defaults there could
                // silently pick a hosted provider and spend .env credentials.
                Console.printLineErr(Style.red(
                  s"Refusing non-interactive startup because ${paths.configYaml} is invalid: $err. " +
                    "Repair the file or pass --ignore-user-config to run with built-in defaults."
                )).andThen(Sync.defer {
                  java.lang.System.exit(1)
                  ()
                })
              case Present(err) =>
                Console.printLineErr(
                  Style.red(s"warning: ${paths.configYaml} unparseable ($err); using defaults")
                ).andThen(apollo.config.Secrets.applyTo(config).map(c => withObs(c)(dispatch(args, c, paths))))
              case Absent =>
                apollo.config.Secrets.applyTo(config).map(c => withObs(c)(dispatch(args, c, paths)))
          }
      }

  /** Install apollo's observability logging for the run: enable OTLP-logs
    * capture when configured, and (unless the level is silent) route apollo's
    * structured `Log` events to an apollo console logger at that level. Silent
    * (the default) leaves the ambient quiet logger and clean chat UI untouched. */
  private def withObs[A, S](c: ApolloConfig)(body: A < S)(using Frame): A < S =
    apollo.obs.ObsLog.setCapture(c.otlpLogsEnabled && c.otlpEndpoint.nonEmpty)
    apollo.obs.ObsLog.withLogger(apollo.obs.ObsLog.parseLevel(c.obsLogLevel))(body)

  private def dispatch(args: CliArgs, config: ApolloConfig, paths: ApolloPaths): Unit < (Sync & Async & Scope) =
    args.command.getOrElse("chat") match
      case "chat"     => runChat(args, config, paths)
      case "model"    => Commands.model(args, config)
      case "config"   => Commands.config(args, config, paths)
      case "sessions" => Commands.sessions(args, paths)
      case "skills"   => Commands.skills(args, config, paths)
      case "cron"     => Commands.cron(args, config, paths)
      case "setup"    => SetupWizard.run(config, paths)
      case "status" | "doctor" => Commands.status(config, paths)
      case "tools"    => Commands.tools(config)
      case "gateway"  => apollo.gateway.Gateway.command(args.commandArgs, config, paths)
      case "mcp"      => Commands.mcp(args, config, paths)
      case "auth"     => Commands.auth(args, config, paths)
      case "secrets"  => Commands.secrets(args, config, paths)
      case "monitoring" => Commands.monitoring(args, config, paths)
      case "lsp"      => Commands.lsp(args, config, paths)
      case "acp"      => apollo.acp.AcpServer.run(config, paths)
      case "memory"   => Commands.memory(paths)
      case "logs"     => Console.printLine(s"session transcripts: ${paths.home.resolve("scala-state").resolve("sessions")}")
      case "version"  => Console.printLine(s"apollo ${BuildInfo.version}")
      case "help" | _ => Console.printLine(helpText)

  // --- chat (REPL + one-shot) --------------------------------------------

  private def runChat(args: CliArgs, config: ApolloConfig, paths: ApolloPaths): Unit < (Sync & Async & Scope) =
    val overrides = RuntimeOverrides(
      model = args.model,
      provider = args.provider,
      reasoning = args.reasoning
    )
    Abort.run[ResolveError](Runtime.resolve(config, overrides)).map {
      case Result.Failure(err) =>
        Console.printLine(Style.red(err.message)).andThen {
          // First-run guard: offer setup on an interactive terminal.
          err match
            case ResolveError.NoCredentials(_) if args.oneshot.isEmpty =>
              SetupWizard.run(config, paths)
            case _ => Sync.defer(())
        }
      case Result.Panic(e) => Console.printLine(Style.red(s"resolution failed: ${e.getMessage}"))
      case Result.Success(runtime) =>
        assembleSession(args, config, paths, runtime).map { session =>
          args.oneshot match
            case Present(prompt) => runOneShot(session, prompt)
            case Absent =>
              args.query match
                case Present(q) if args.quiet => runOneShot(session, q)
                case Present(q)               => seedAndRepl(session, q)
                case Absent                   => session.repl.run
        }
    }

  private final case class Session(
      runtime: ResolvedRuntime,
      agent: Agent,
      toolCtx: ToolContext,
      store: SessionStore,
      sessionId: String,
      toolNames: List[String],
      repl: Repl,
      interruptFlag: java.util.concurrent.atomic.AtomicBoolean
  )

  private def assembleSession(
      args: CliArgs,
      config: ApolloConfig,
      paths: ApolloPaths,
      runtime: ResolvedRuntime
  ): Session < (Sync & Async) =
    for
      editor        <- PlatformEditor.create
      store          = new SessionStore(paths)
      now           <- Sync.defer(java.time.Instant.now())
      resumeTarget   = args.resume.orElse(args.continueSession.map(c => if c.isEmpty then "latest" else c))
      resumedV       = (resumeTarget match
                         case Present(target) => store.find(target)
                         case Absent          => Maybe.empty[SessionMeta]
                       ): Maybe[SessionMeta] < Sync
      resumed       <- resumedV
      sessionId      = resumed.map(_.id).getOrElse(store.newSessionId(now))
      cwd           <- Sync.defer(Paths.get(".").toAbsolutePath.normalize)
      interruptFlag  = new java.util.concurrent.atomic.AtomicBoolean(false)
      todoRef       <- AtomicRef.init(List.empty[TodoItem])
      skills         = new SkillStore(config, paths)
      // A quiet `-q` run answers and exits with no interactive prompt, so its
      // dangerous-command policy follows approvals.single_query_mode.
      singleQuery    = args.oneshot.isEmpty && args.quiet && args.query.nonEmpty
      approvals      = new ApprovalService(config, paths, "cli", args.oneshot.nonEmpty, args.yolo, singleQuery)
      // MCP servers spawn before toolset selection so their tools reach this
      // session. An explicit -t list acts as the upstream server allowlist.
      _             <- apollo.mcp.McpManager.start(config, paths, cwd,
                         args.toolsets.map(_.toList), BuildInfo.version)
      // Server→client MCP requests: sampling runs a model call; elicitation asks
      // the user (declines when non-interactive).
      _              = apollo.mcp.McpManager.setSamplingHandler(mcpSamplingHandler(runtime))
      _              = apollo.mcp.McpManager.setElicitationHandler(mcpElicitationHandler(editor))
      toolCtx        = ToolContext(
                         config = config,
                         paths = paths,
                         cwd = cwd,
                         platform = "cli",
                         sessionId = sessionId,
                         approvals = approvals,
                         ui = new EditorToolUi(editor),
                         todo = todoRef,
                         skills = skills,
                         sessionSearch = Present((q, n) => store.search(q, n))
                       )
      toolNames      = Toolsets.select(
                         args.toolsets.map(_.toList).orElse(config.platformToolsets("cli").map(_.toList)) match
                           case Present(sets) => Some(sets)
                           case Absent        => Some(List("apollo-cli"))
                         ,
                         config.disabledToolsets,
                         mcpDefault = args.toolsets.isEmpty
                       )
      agent          = new Agent(runtime, toolCtx, store, sessionId,
                         maxIterations = config.maxTurns, interruptFlag = interruptFlag)
      ctxWithDelegate = toolCtx.copy(
                          delegate = Present(delegateRunner(runtime, toolCtx, store, config)),
                          vision   = Present(visionRunner(runtime, toolCtx, store, config)))
      agentFinal     = new Agent(runtime, ctxWithDelegate, store, sessionId,
                         maxIterations = config.maxTurns, interruptFlag = interruptFlag)
      historyV       = (resumed match
                         case Present(meta) => store.loadTranscript(meta.id)
                         case Absent        => List.empty[Message]
                       ): List[Message] < Sync
      history       <- historyV
      _              = agentFinal.restore(history)
      _             <- resumed match
                         case Present(_) => Sync.defer(())
                         case Absent =>
                           store.create(SessionMeta(
                             id = sessionId, title = Absent, platform = "cli",
                             model = runtime.model, provider = runtime.providerSlug,
                             startedAt = now.toEpochMilli / 1000.0, endedAt = Absent,
                             cwd = cwd.toString, messageCount = 0, apiCalls = 0,
                             usage = apollo.core.Usage.zero
                           ))
      repl           = new Repl(editor, runtime, agentFinal, ctxWithDelegate, store, sessionId,
                         toolNames, interruptFlag)
    yield Session(runtime, agentFinal, ctxWithDelegate, store, sessionId, toolNames, repl, interruptFlag)

  /** delegate_task backing: spawns a child Agent with narrowed toolsets, a
    * fresh session, and the delegation iteration cap.
    */
  private def delegateRunner(
      runtime: ResolvedRuntime,
      parentCtx: ToolContext,
      store: SessionStore,
      config: ApolloConfig
  ): DelegateRunner =
    new DelegateRunner:
      def run(goal: String, context: String, toolsets: List[String]): String < (Sync & Async) =
        for
          now      <- Sync.defer(java.time.Instant.now())
          childId   = store.newSessionId(now)
          todoRef  <- AtomicRef.init(List.empty[TodoItem])
          childCtx  = parentCtx.copy(
                        platform = "subagent",
                        sessionId = childId,
                        todo = todoRef,
                        delegate = Absent, // children cannot delegate (flat tree)
                        ui = UnattendedToolUi
                      )
          childTools = Toolsets.select(Some(toolsets), config.disabledToolsets,
                           mcpDefault = config.delegationInheritMcpToolsets)
                         .filterNot(Set("delegate_task", "clarify", "memory", "cronjob_manage").contains)
          flag      = new java.util.concurrent.atomic.AtomicBoolean(false)
          child     = new Agent(runtime, childCtx, store, childId,
                        maxIterations = Present(config.delegationMaxIterations), interruptFlag = flag)
          _        <- store.create(SessionMeta(
                        id = childId, title = Present(s"subagent: ${goal.take(40)}"),
                        platform = "subagent", model = runtime.model, provider = runtime.providerSlug,
                        startedAt = now.toEpochMilli / 1000.0, endedAt = Absent, cwd = parentCtx.cwd.toString,
                        messageCount = 0, apiCalls = 0, usage = apollo.core.Usage.zero
                      ))
          system   <- SystemPrompt.build(SystemPrompt.Input(
                        config, parentCtx.paths, parentCtx.skills, parentCtx.cwd,
                        "subagent", runtime.model, runtime.providerSlug, childTools
                      ))
          prompt    = if context.isEmpty then goal else s"$goal\n\nContext:\n$context"
          result   <- child.runTurn(Message.user(prompt), system, childTools, TurnCallbacks())
        yield
          if result.finalResponse.nonEmpty then result.finalResponse.take(24000)
          else s"[subagent ended: ${result.exitReason}]"

  /** Backs the vision_analyze tool: a single vision-model call with the image
    * attached (no tools, one turn). Reuses the main runtime + wire transports,
    * which already serialize Content.Image. */
  private def visionRunner(
      runtime: ResolvedRuntime,
      parentCtx: ToolContext,
      store: SessionStore,
      config: ApolloConfig
  ): VisionRunner =
    new VisionRunner:
      def analyze(mediaType: String, base64: String, prompt: String): String < (Sync & Async) =
        for
          now     <- Sync.defer(java.time.Instant.now())
          childId  = store.newSessionId(now)
          todoRef <- AtomicRef.init(List.empty[TodoItem])
          childCtx = parentCtx.copy(platform = "vision", sessionId = childId, todo = todoRef,
                       delegate = Absent, vision = Absent, ui = UnattendedToolUi)
          flag     = new java.util.concurrent.atomic.AtomicBoolean(false)
          child    = new Agent(runtime, childCtx, store, childId, maxIterations = Present(1), interruptFlag = flag)
          _       <- store.create(SessionMeta(
                       id = childId, title = Present("vision"), platform = "vision",
                       model = runtime.model, provider = runtime.providerSlug,
                       startedAt = now.toEpochMilli / 1000.0, endedAt = Absent, cwd = parentCtx.cwd.toString,
                       messageCount = 0, apiCalls = 0, usage = apollo.core.Usage.zero))
          system  <- SystemPrompt.build(SystemPrompt.Input(
                       config, parentCtx.paths, parentCtx.skills, parentCtx.cwd,
                       "vision", runtime.model, runtime.providerSlug, Nil))
          msg      = Message(Role.User, List(Content.Image(mediaType, base64), Content.Text(prompt)), Absent)
          result  <- child.runTurn(msg, system, Nil, TurnCallbacks())
        yield
          if result.finalResponse.nonEmpty then result.finalResponse.take(24000)
          else s"[vision call ended: ${result.exitReason}]"

  /** Backs MCP `sampling/createMessage`: one non-streaming model call with the
    * server-supplied messages, returned in the MCP result shape. */
  private def mcpSamplingHandler(runtime: ResolvedRuntime): Value => Result[String, Value] < (Sync & Async) =
    params =>
      val msgs = apollo.mcp.McpSampling.parseMessages(params)
      val rt   = runtime.copy(streaming = false, maxTokens = apollo.mcp.McpSampling.maxTokens(params).orElse(runtime.maxTokens))
      WireTransport.forMode(rt.apiMode) match
        case Result.Success(transport) =>
          val req = TurnRequest(rt, apollo.mcp.McpSampling.systemPrompt(params), msgs, Nil)
          Abort.run[HttpError](transport.streamTurn(req)(_ => ())).map {
            case Result.Success(resp) =>
              val text = resp.message.content.collect { case Content.Text(t) => t }.mkString
              Result.succeed(apollo.mcp.McpSampling.result(text, rt.model))
            case Result.Failure(e) => Result.fail(s"sampling failed: ${e.getMessage}")
            case Result.Panic(e)   => Result.fail(s"sampling failed: ${String.valueOf(e.getMessage)}")
          }
        case _ => Result.fail(s"sampling: no transport for ${rt.apiMode}")

  /** Backs MCP `elicitation/create`: asks the user (interactive) or declines. */
  private def mcpElicitationHandler(editor: LineEditor): Value => Result[String, Value] < (Sync & Async) =
    params =>
      val message = (params / "message").asStr.getOrElse("The MCP server is requesting input.")
      editor.isInteractive.map { interactive =>
        if !interactive then Result.succeed(apollo.mcp.McpElicitation.decline)
        else
          editor.readLine(s"[MCP wants input] $message\n> ").map {
            case Present(answer) if answer.trim.nonEmpty =>
              Result.succeed(apollo.mcp.McpElicitation.accept(apollo.mcp.McpElicitation.singleStringField(params).getOrElse("value"), answer.trim))
            case _ => Result.succeed(apollo.mcp.McpElicitation.cancel)
          }
      }

  private def runOneShot(session: Session, prompt: String): Unit < (Sync & Async) =
    for
      system <- SystemPrompt.build(SystemPrompt.Input(
                  session.toolCtx.config, session.toolCtx.paths, session.toolCtx.skills,
                  session.toolCtx.cwd, "cli", session.runtime.model, session.runtime.providerSlug,
                  session.toolNames
                ))
      result <- session.agent.runTurn(Message.user(prompt), system, session.toolNames, TurnCallbacks())
      _      <- Console.printLine(result.finalResponse)
      _      <- (if result.exitReason != "text_response" then
                   Console.printLineErr(Style.red(s"turn ended: ${result.exitReason}"))
                 else Sync.defer(())): Unit < (Sync & Async)
    yield ()

  private def seedAndRepl(session: Session, query: String): Unit < (Sync & Async) =
    // -q on a TTY seeds the interactive session with a first turn.
    session.repl.bannerText.map(Console.printLine).andThen {
      session.repl.runSeeded(query)
    }

  val helpText: String =
    s"""apollo ${BuildInfo.version} — a Scala + Kyo clone of the upstream agent harness
       |
       |Usage: apollo [flags] [command]
       |
       |Commands:
       |  chat           interactive chat (default)
       |  model          show the resolved provider/model
       |  config         show config / paths (get <key>, path)
       |  sessions       list stored sessions
       |  memory         print MEMORY.md and USER.md
       |  skills         list installed skills
       |  tools          list toolsets and tools
       |  cron           list scheduled jobs (run-scheduler to start the tick loop)
       |  mcp            list configured MCP servers (test <name> probes one)
       |  auth           provider OAuth: <copilot|qwen> [login|status|logout]
       |  secrets        secret sources: status | resolve (values masked)
       |  monitoring     observability / OTLP export status
       |  lsp            configured language servers
       |  acp            run the Agent Client Protocol server (editor integration)
       |  gateway        run the messaging gateway (telegram / api server / webhook)
       |  setup          interactive provider setup wizard
       |  status         configuration health check (alias: doctor)
       |  logs           show the session transcripts directory
       |
       |Flags:
       |  -z, --oneshot PROMPT   one-shot: print the final response and exit
       |  -q, --query TEXT       seed the first turn (with -Q: answer and exit)
       |  -m, --model MODEL      model override
       |  --provider NAME        provider override (${Profiles.autoDetectOrder.take(6).mkString(", ")}, ...)
       |  --reasoning LEVEL      none|minimal|low|medium|high|xhigh|max
       |  -t, --toolsets LIST    comma-separated toolsets for this run
       |  -r, --resume SESSION   resume a session by id/title ("latest")
       |  -c, --continue [NAME]  resume the most recent session
       |  -p, --profile NAME     use profile $$APOLLO_HOME/profiles/NAME
       |  --yolo                 bypass command approvals
       |  --ignore-user-config   skip config.yaml
       |  -V, --version          version""".stripMargin
end Cli

/** ToolUi bound to the interactive editor. */
final class EditorToolUi(editor: LineEditor) extends ToolUi:
  def requestApproval(prompt: String): ApprovalDecision < (Sync & Async) =
    Console.printLine(Style.gold(prompt) + "\n  [y] once  [s] session  [a] always  [n] deny").andThen {
      editor.readLine("approve? ").map {
        case Present("y") | Present("Y") | Present("") => ApprovalDecision.Once
        case Present("s") | Present("S")               => ApprovalDecision.Session
        case Present("a") | Present("A")               => ApprovalDecision.Always
        case _                                          => ApprovalDecision.Deny
      }
    }

  def clarify(questions: List[ClarifyQuestion]): List[String] < (Sync & Async) =
    Kyo.foreach(questions) { q =>
      val choiceLines = q.choices.zipWithIndex.map((c, i) => s"  ${i + 1}. $c").mkString("\n")
      Console.printLine(Style.gold(q.question) + (if choiceLines.nonEmpty then s"\n$choiceLines" else ""))
        .andThen(editor.readLine("> ").map {
          case Present(answer) =>
            answer.toIntOption.flatMap(i => q.choices.lift(i - 1)).getOrElse(answer)
          case Absent => ""
        })
    }.map(_.toList)

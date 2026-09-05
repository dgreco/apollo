package apollo.cli

import apollo.agent.{Agent, SystemPrompt, TurnCallbacks}
import apollo.core.*
import apollo.provider.{Profiles, ResolvedRuntime}
import apollo.session.SessionStore
import apollo.tools.{ToolContext, Toolsets}
import kyo.*

/** ANSI styling helpers (degrade to plain text when NO_COLOR is set). */
object Style:
  private def colorEnabled = !sys.env.contains("NO_COLOR")
  private def wrap(code: String, s: String) = if colorEnabled then s"\u001b[${code}m$s\u001b[0m" else s
  def dim(s: String)    = wrap("2", s)
  def bold(s: String)   = wrap("1", s)
  def gold(s: String)   = wrap("38;5;178", s)
  def red(s: String)    = wrap("31", s)
  def green(s: String)  = wrap("32", s)

/** The interactive chat REPL: banner, prompt loop, slash commands, streamed
  * rendering, tool progress lines, Ctrl-C interrupt-and-continue.
  */
final class Repl(
    editor: LineEditor,
    runtime0: ResolvedRuntime,
    agent: Agent,
    toolCtx: ToolContext,
    store: SessionStore,
    sessionId: String,
    toolNames: List[String],
    interruptFlag: java.util.concurrent.atomic.AtomicBoolean
):
  private var runtime      = runtime0
  private var showThinking = toolCtx.config.showReasoning
  private var toolProgress = toolCtx.config.toolProgress != "off"

  def banner: String =
    val skillsLine = s"session ${Style.dim(sessionId)}"
    s"""${Style.gold("☀ apollo")} — ${runtime.displayName} · ${Style.bold(runtime.model)}
       |$skillsLine · ${toolNames.length} tools · /help for commands""".stripMargin

  def run: Unit < (Sync & Async) =
    Console.printLine(banner).andThen(loop)

  /** Runs one seeded turn (the `-q` flag on a TTY) then continues interactively. */
  def runSeeded(query: String): Unit < (Sync & Async) =
    runTurn(query).andThen(loop)

  private def loop: Unit < (Sync & Async) =
    editor.readLine(Style.gold("☀ ") + "").map {
      case Absent => Console.printLine(Style.dim("bye"))
      case Present(line) =>
        val trimmed = line.trim
        if trimmed.isEmpty then loop
        else if trimmed.startsWith("/") then
          handleSlash(trimmed).map(continue => if continue then loop else ())
        else runTurn(trimmed).andThen(loop)
    }

  private def runTurn(input: String): Unit < (Sync & Async) =
    for
      _      <- Sync.defer(editor.onInterrupt(() => interruptFlag.set(true)))
      system <- SystemPrompt.build(SystemPrompt.Input(
                  config = toolCtx.config,
                  paths = toolCtx.paths,
                  skills = toolCtx.skills,
                  cwd = toolCtx.cwd,
                  platform = "cli",
                  model = runtime.model,
                  provider = runtime.providerSlug,
                  toolNames = toolNames
                ))
      result <- agent.runTurn(Message.user(input), system, toolNames, callbacks)
      _      <- Console.printLine("")
      _      <- if result.interrupted then Console.printLine(Style.red("· interrupted"))
                else if result.exitReason.startsWith("error") then
                  Console.printLine(Style.red(s"· ${result.exitReason}"))
                else Sync.defer(())
    yield ()

  private def callbacks: TurnCallbacks =
    TurnCallbacks(
      onTextDelta = t => Sync.defer { print(t); java.lang.System.out.flush() },
      onThinkingDelta = t =>
        if showThinking then Sync.defer { print(Style.dim(t)); java.lang.System.out.flush() }
        else (),
      onToolStart = (name, args) =>
        if toolProgress then Console.printLine(Style.dim(s"\n┊ $name ${args.take(120)}")) else (),
      onToolComplete = (name, preview, isError) =>
        if toolProgress && isError then Console.printLine(Style.red(s"┊ $name failed: ${preview.take(160)}"))
        else (),
      onStatus = msg => if toolProgress then Console.printLine(Style.dim(s"┊ $msg")) else ()
    )

  /** Slash commands (the upstream in-session command set this build supports).
    * Returns false to exit the REPL.
    */
  private def handleSlash(command: String): Boolean < (Sync & Async) =
    val parts = command.drop(1).split("\\s+", 2)
    val name  = parts.head.toLowerCase
    val arg   = if parts.length > 1 then parts(1).trim else ""
    name match
      case "quit" | "exit" | "q" => false
      case "help" =>
        Console.printLine(
          """/help                 this help
            |/model [name]         show or switch the model (provider:model or bare id)
            |/reasoning <level>    none|minimal|low|medium|high|xhigh|max
            |/reset | /new         clear the conversation
            |/compress             force context compression
            |/history              show turn count and token usage
            |/tools                list active tools
            |/skills               list available skills
            |/verbose              toggle tool progress display
            |/reasoning-display    toggle thinking display
            |/yolo                 toggle approval bypass for this session
            |/status               session status
            |/quit                 exit""".stripMargin
        ).andThen(true)
      case "model" =>
        if arg.isEmpty then
          Console.printLine(s"model: ${runtime.model} (provider: ${runtime.providerSlug})").andThen(true)
        else
          switchModel(arg).andThen(true)
      case "reasoning" =>
        apollo.provider.Reasoning.fromConfigValue(arg) match
          case Present(cfg) =>
            runtime = runtime.copy(reasoning = Present(cfg))
            Console.printLine(s"reasoning effort: ${cfg.effort}").andThen(true)
          case Absent =>
            Console.printLine("usage: /reasoning none|minimal|low|medium|high|xhigh|max").andThen(true)
      case "reset" | "new" =>
        agent.restore(Nil)
        Console.printLine("conversation cleared").andThen(true)
      case "history" | "status" =>
        val h = agent.history
        Console.printLine(
          s"session $sessionId — ${h.length} messages, model ${runtime.model} (${runtime.providerSlug})"
        ).andThen(true)
      case "tools" =>
        Console.printLine(toolNames.sorted.mkString(", ")).andThen(true)
      case "skills" =>
        toolCtx.skills.scan.map { skills =>
          if skills.isEmpty then Console.printLine("no skills installed")
          else
            Console.printLine(skills.sortBy(s => (s.category, s.name))
              .map(s => s"${s.category}/${s.name}: ${s.description}").mkString("\n"))
        }.andThen(true)
      case "verbose" =>
        toolProgress = !toolProgress
        Console.printLine(s"tool progress: ${if toolProgress then "on" else "off"}").andThen(true)
      case "reasoning-display" =>
        showThinking = !showThinking
        Console.printLine(s"thinking display: ${if showThinking then "on" else "off"}").andThen(true)
      case "compress" =>
        Console.printLine("compression will run before the next model call").andThen(true)
      case "yolo" =>
        Console.printLine("yolo toggle is per-invocation in apollo: restart with --yolo").andThen(true)
      case other =>
        Console.printLine(s"unknown command: /$other (try /help)").andThen(true)
  end handleSlash

  private def switchModel(spec: String): Unit < (Sync & Async) =
    // provider:model selector (upstream parse_model_input); bare id keeps provider.
    val (providerPart, modelPart) =
      spec.indexOf(':') match
        case i if i > 0 && Profiles.find(spec.take(i)).nonEmpty => (Present(spec.take(i)), spec.drop(i + 1))
        case _                                                  => (Absent, spec)
    providerPart match
      case Present(slug) =>
        Profiles.find(slug) match
          case Present(profile) if !profile.unsupported =>
            val key = profile.keyEnvVars.foldLeft(Maybe.empty[String])((a, v) => a.orElse(toolCtx.config.env.get(v)))
            runtime = runtime.copy(
              providerSlug = profile.name,
              displayName = profile.displayName,
              model = modelPart,
              baseUrl = profile.baseUrl,
              apiKey = key,
              apiMode = profile.apiMode,
              profile = Present(profile)
            )
            Console.printLine(s"switched to ${profile.name}:$modelPart")
          case Present(profile) =>
            Console.printLine(Style.red(s"provider ${profile.name} unsupported: ${profile.unsupportedReason}"))
          case Absent => Console.printLine(Style.red(s"unknown provider: $slug"))
      case Absent =>
        runtime = runtime.copy(model = modelPart)
        Console.printLine(s"switched model to $modelPart")
end Repl

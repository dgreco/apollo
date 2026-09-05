package apollo.cli

import apollo.agent.{Agent, SystemPrompt, TurnCallbacks, TurnResult}
import apollo.core.*
import apollo.config.Fs
import apollo.cron.CronStore
import apollo.util.Crypto
import apollo.mcp.McpManager
import apollo.provider.{Profiles, ResolvedRuntime}
import apollo.session.{SessionStore, SessionMeta}
import apollo.tools.{ToolContext, Toolsets, ToolRegistry}
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
  private var sess         = sessionId // tracks the active session (changes on /resume)
  private var pendingImages = List.empty[Content.Image] // attached via /image, sent with the next turn
  private var goal: Maybe[String] = Absent              // /goal — standing objective injected each turn
  private var queued        = List.empty[String]        // /queue — prompts to run after the next turn

  def banner: String =
    val skillsLine = s"session ${Style.dim(sess)}"
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
        else runTurn(trimmed).andThen(drainQueue).andThen(loop)
    }

  /** Run any prompts stacked with /queue, in order, after a completed turn. */
  private def drainQueue: Unit < (Sync & Async) =
    queued match
      case Nil => Sync.defer(())
      case next :: rest =>
        queued = rest
        Console.printLine(Style.dim(s"· queued: $next")).andThen(runTurn(next)).andThen(drainQueue)

  private def runTurn(
      input: String,
      tools: List[String] = toolNames,
      systemSuffix: String = ""
  ): TurnResult < (Sync & Async) =
    for
      _      <- Sync.defer(editor.onInterrupt(() => interruptFlag.set(true)))
      base   <- SystemPrompt.build(SystemPrompt.Input(
                  config = toolCtx.config,
                  paths = toolCtx.paths,
                  skills = toolCtx.skills,
                  cwd = toolCtx.cwd,
                  platform = "cli",
                  model = runtime.model,
                  provider = runtime.providerSlug,
                  toolNames = tools
                ))
      withSuffix = if systemSuffix.isEmpty then base else s"$base\n\n$systemSuffix"
      system  = goal match
                  case Present(g) => s"$withSuffix\n\nStanding goal (keep working toward this across turns): $g"
                  case Absent     => withSuffix
      userMsg = if pendingImages.isEmpty then Message.user(input)
                else Message(Role.User, pendingImages.reverse :+ Content.Text(input), Absent)
      _       = pendingImages = Nil // consumed
      result <- agent.runTurn(userMsg, system, tools, callbacks)
      _      <- Console.printLine("")
      _      <- if result.interrupted then Console.printLine(Style.red("· interrupted"))
                else if result.exitReason.startsWith("error") then
                  Console.printLine(Style.red(s"· ${result.exitReason}"))
                else Sync.defer(())
    yield result

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
        Console.printLine(helpText).andThen(true)
      case "version" | "v" =>
        Console.printLine(s"apollo ${Cli.version} · ${runtime.displayName} ${runtime.model}").andThen(true)
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
        Console.printLine(ReplCommands.statusLines(
          sess, runtime.model, runtime.providerSlug,
          agent.history.length, agent.apiCallCount, agent.usageSnapshot,
          agent.lastPromptTokenCount, runtime.contextLength.getOrElse(200_000))
        ).andThen(true)
      case "config" =>
        val c = toolCtx.config
        val pairs = List(
          "model"            -> s"${runtime.model} (${runtime.providerSlug})",
          "home"             -> toolCtx.paths.home.toString,
          "profile"          -> ReplCommands.profileName(toolCtx.paths.home),
          "approvals"        -> toolCtx.approvals.currentApprovalMode,
          "yolo"             -> (if toolCtx.approvals.yoloEnabled then "on" else "off"),
          "compression"      -> (if c.compressionEnabled then s"on (threshold ${c.compressionThreshold})" else "off"),
          "context window"   -> runtime.contextLength.getOrElse(200_000).toString,
          "memory"           -> (if c.memoryEnabled then "enabled" else "disabled"),
          "terminal backend" -> c.terminalBackend,
          "active tools"     -> toolNames.length.toString
        )
        Console.printLine(ReplCommands.formatKeyValues(pairs)).andThen(true)
      case "tools" =>
        Console.printLine(toolNames.sorted.mkString(", ")).andThen(true)
      case "skills" =>
        toolCtx.skills.scan.map { skills =>
          if skills.isEmpty then Console.printLine("no skills installed")
          else
            Console.printLine(skills.sortBy(s => (s.category, s.name))
              .map(s => s"${s.category}/${s.name}: ${s.description}").mkString("\n"))
        }.andThen(true)
      case "mcp" =>
        Console.printLine(ReplCommands.formatMcp(McpManager.status, ToolRegistry.dynamicToolsets)).andThen(true)
      case "cron" =>
        new CronStore(toolCtx.paths).load.map(jobs => Console.printLine(ReplCommands.formatCron(jobs))).andThen(true)
      case "memory" =>
        Fs.readString(toolCtx.paths.memoryMd)
          .map(mem => Console.printLine(mem.getOrElse("(no memory recorded)"))).andThen(true)
      case "sessions" =>
        store.list.map(metas => Console.printLine(ReplCommands.formatSessions(metas))).andThen(true)
      case "resume" =>
        if arg.isEmpty then Console.printLine("usage: /resume <id|title|latest>").andThen(true)
        else doResume(arg)
      case "save" => doSave(arg)
      case "retry" => doRetry
      case "copy"  => doCopy
      case "verbose" =>
        toolProgress = !toolProgress
        Console.printLine(s"tool progress: ${if toolProgress then "on" else "off"}").andThen(true)
      case "reasoning-display" =>
        showThinking = !showThinking
        Console.printLine(s"thinking display: ${if showThinking then "on" else "off"}").andThen(true)
      case "compress" | "compact" =>
        agent.requestCompress()
        Console.printLine("compaction requested — runs before the next model call").andThen(true)
      case "yolo" =>
        val now = !toolCtx.approvals.yoloEnabled
        toolCtx.approvals.setYolo(now)
        Console.printLine(
          if now then Style.red("yolo ON") + " — dangerous-command approvals bypassed"
          else "yolo off"
        ).andThen(true)
      case "approvals" =>
        arg.toLowerCase match
          case "" =>
            Console.printLine(s"approval mode: ${toolCtx.approvals.currentApprovalMode}" +
              (if toolCtx.approvals.yoloEnabled then " (yolo on)" else "")).andThen(true)
          case m @ ("manual" | "off") =>
            toolCtx.approvals.setApprovalMode(m)
            Console.printLine(s"approval mode: $m").andThen(true)
          case _ =>
            Console.printLine("usage: /approvals [manual|off]").andThen(true)
      case "clear" =>
        agent.restore(Nil)
        Sync.defer(print(clearScreen)).andThen(Console.printLine("cleared — fresh conversation")).andThen(true)
      case "redraw" =>
        Sync.defer(print(clearScreen)).andThen(Console.printLine(banner)).andThen(true)
      case "title" =>
        if arg.isEmpty then Console.printLine("usage: /title <name>").andThen(true)
        else store.updateMeta(sess)(_.copy(title = Present(arg)))
          .andThen(Console.printLine(s"title set: $arg")).andThen(true)
      case "profile" =>
        Console.printLine(s"profile ${ReplCommands.profileName(toolCtx.paths.home)} · home ${toolCtx.paths.home}").andThen(true)
      case "whoami" =>
        Console.printLine("cli · full access (apollo has no admin/user role split)").andThen(true)
      case "usage" =>
        val u = agent.usageSnapshot
        Console.printLine(s"usage: ${ReplCommands.formatUsage(u)} · total ${u.total} · ${agent.apiCallCount} api calls").andThen(true)
      case "diff" => doDiff(arg)
      case "reload-skills" | "reload_skills" =>
        toolCtx.skills.scan.map(sk => Console.printLine(s"rescanned skills: ${sk.length} installed")).andThen(true)
      case "plan" =>
        if arg.isEmpty then Console.printLine("usage: /plan <task>").andThen(true)
        else runTurn(arg, tools = Nil, systemSuffix = planInstruction).andThen(true)
      case "init" =>
        runTurn(if arg.isEmpty then initInstruction else s"$initInstruction\n\nExtra notes: $arg").andThen(true)
      case "branch" | "fork" => doBranch(arg)
      case "bg" =>
        if arg.isEmpty then Console.printLine("usage: /bg <prompt>").andThen(true) else doBg(arg)
      case "agents" | "tasks" =>
        Console.printLine(ReplCommands.formatJobs(BackgroundSessions.all)).andThen(true)
      case "stop" => doStop(arg)
      case "loop" | "proactive" => doLoopCmd(arg)
      case "review" => doReview(arg)
      case "image" =>
        if arg.isEmpty then Console.printLine("usage: /image <path>").andThen(true) else doImage(arg)
      case "worktree" => doWorktree(arg)
      case "snapshot" | "snap" => doSnapshot(arg)
      case "rollback" => doRollback(arg)
      case "prompt" | "compose" => doPrompt(arg)
      case "goal" => doGoal(arg)
      case "queue" => doQueue(arg)
      case "moa" =>
        if arg.isEmpty then Console.printLine("usage: /moa <prompt>").andThen(true) else doMoa(arg, 3)
      case "learn" =>
        if arg.isEmpty then Console.printLine("usage: /learn <what to capture as a skill>").andThen(true)
        else runTurn(
          "Create a reusable skill capturing the following, using your skill-management tool " +
            "(give it a name, a concise description, and the steps/knowledge). If no skill tool is " +
            s"available, say so instead.\n\nCapture: $arg"
        ).andThen(true)
      case other =>
        Console.printLine(s"unknown command: /$other (try /help)").andThen(true)
  end handleSlash

  private val clearScreen = "[2J[3J[H"

  private val planInstruction =
    "Produce a concise, numbered implementation plan for the task. Do NOT run tools or make changes — planning only."

  private val initInstruction =
    "Scan this repository and write or update an AGENTS.md at the repo root describing the project, " +
      "the build/test commands, the layout, and the conventions an agent should follow. Use your file tools."

  private def q(s: String): String = ReplCommands.shellQuote(s)

  private def sh(cmd: String): Result[CommandException, String] < (Sync & Async) =
    Abort.run[CommandException](Command("sh", "-c", cmd).text)

  /** Run a git command in the working dir and print its output. */
  private def runGit(gitCmd: String): Boolean < (Sync & Async) =
    sh(s"cd ${q(toolCtx.cwd.toString)} && $gitCmd").map {
      case Result.Success(out) => Console.printLine(if out.trim.isEmpty then "(ok)" else out)
      case Result.Failure(e)   => Console.printLine(Style.red(s"git failed: ${e.getMessage}"))
      case _                   => Console.printLine(Style.red("git failed"))
    }.andThen(true)

  private def doDiff(arg: String): Boolean < (Sync & Async) =
    runGit(if arg.isEmpty then "git diff" else s"git diff $arg")

  private def doImage(pathStr: String): Boolean < (Sync & Async) =
    ReplCommands.imageMediaType(pathStr) match
      case None => Console.printLine(s"unsupported image type: $pathStr (png/jpg/gif/webp)").andThen(true)
      case Some(mt) =>
        val p = toolCtx.cwd.resolve(pathStr)
        Fs.readBytes(p).map {
          case Present(bytes) =>
            pendingImages = Content.Image(mt, Crypto.base64(bytes)) :: pendingImages
            Console.printLine(Style.dim(s"attached ${bytes.length} bytes ($mt) — sent with your next message"))
          case Absent =>
            Console.printLine(s"file not found: $p")
        }.andThen(true)

  private def doWorktree(arg: String): Boolean < (Sync & Async) =
    ReplCommands.worktreeCommand(arg) match
      case Some(cmd) => runGit(cmd)
      case None      => Console.printLine("usage: /worktree [list | new [name] | prune [--dry-run]]").andThen(true)

  private def doSnapshot(arg: String): Boolean < (Sync & Async) =
    val stateDir = toolCtx.paths.home.resolve("scala-state")
    val snapsDir = toolCtx.paths.home.resolve("snapshots")
    arg.split("\\s+").toList.filter(_.nonEmpty) match
      case Nil | ("list" :: _) =>
        Fs.exists(snapsDir).map { ex =>
          if !ex then Console.printLine("no snapshots")
          else Fs.list(snapsDir).map { entries =>
            val ids = entries.map(_.getFileName.toString).sorted
            Console.printLine(if ids.isEmpty then "no snapshots" else ids.mkString("\n"))
          }
        }.andThen(true)
      case "create" :: _ =>
        Sync.defer(java.time.Instant.now().toEpochMilli.toString).map { id =>
          val dest = snapsDir.resolve(id)
          sh(s"mkdir -p ${q(dest.toString)} && cp -r ${q(stateDir.toString)}/. ${q(dest.toString)}/").map {
            case Result.Success(_) => Console.printLine(s"snapshot created: $id")
            case _                 => Console.printLine(Style.red("snapshot failed"))
          }
        }.andThen(true)
      case "restore" :: id :: _ =>
        val src = snapsDir.resolve(id)
        Fs.exists(src).map { ex =>
          if !ex then Console.printLine(s"no snapshot $id")
          else sh(s"cp -r ${q(src.toString)}/. ${q(stateDir.toString)}/").map {
            case Result.Success(_) =>
              Console.printLine(Style.red(s"restored snapshot $id — restart apollo to reload session state"))
            case _ => Console.printLine(Style.red("restore failed"))
          }
        }.andThen(true)
      case "prune" :: _ =>
        sh(s"rm -rf ${q(snapsDir.toString)}").map {
          case Result.Success(_) => Console.printLine("removed all snapshots")
          case _                 => Console.printLine(Style.red("prune failed"))
        }.andThen(true)
      case _ =>
        Console.printLine("usage: /snapshot [create | list | restore <id> | prune]").andThen(true)

  // --- /rollback: git working-tree checkpoints (manual create + restore) -----

  /** Snapshot the working tree (tracked + newly-added) into a ref without
    * touching the index/HEAD/working tree, via a throwaway index. */
  private def createCkptCmd(id: String): String =
    "cd " + q(toolCtx.cwd.toString) + " && " +
      "idx=$(git rev-parse --git-path index) && " +
      "tmpidx=$(mktemp) && cp \"$idx\" \"$tmpidx\" 2>/dev/null || true; " +
      "GIT_INDEX_FILE=\"$tmpidx\" git add -A && " +
      "tree=$(GIT_INDEX_FILE=\"$tmpidx\" git write-tree) && " +
      "commit=$(git commit-tree \"$tree\" -p HEAD -m 'apollo checkpoint') && " +
      "git update-ref refs/apollo/ckpt/" + id + " \"$commit\" && " +
      "rm -f \"$tmpidx\""

  private def captureCkpts: List[(String, String)] < (Sync & Async) =
    sh(s"cd ${q(toolCtx.cwd.toString)} && " +
      "git for-each-ref --sort=-refname refs/apollo/ckpt/ --format='%(refname)|%(creatordate:iso)'").map {
      case Result.Success(out) => ReplCommands.parseCheckpoints(out)
      case _                   => Nil
    }

  private def doRollback(arg: String): Boolean < (Sync & Async) =
    arg.split("\\s+").toList.filter(_.nonEmpty) match
      case Nil | ("list" :: _) =>
        captureCkpts.map(cks => Console.printLine(ReplCommands.formatCheckpoints(cks))).andThen(true)
      case "create" :: _ =>
        Sync.defer(java.time.Instant.now().toEpochMilli.toString).map { id =>
          sh(createCkptCmd(id)).map {
            case Result.Success(_) => Console.printLine(s"checkpoint created: $id")
            case Result.Failure(e) => Console.printLine(Style.red(s"checkpoint failed: ${e.getMessage}"))
            case _                 => Console.printLine(Style.red("checkpoint failed"))
          }
        }.andThen(true)
      case n :: _ if n.toIntOption.isDefined =>
        captureCkpts.map { cks =>
          cks.lift(n.toInt - 1) match
            case Some((ref, _)) =>
              sh(s"cd ${q(toolCtx.cwd.toString)} && git checkout ${q(ref)} -- .").map {
                case Result.Success(_) => Console.printLine(s"rolled back to checkpoint $n ($ref)")
                case Result.Failure(e) => Console.printLine(Style.red(s"rollback failed: ${e.getMessage}"))
                case _                 => Console.printLine(Style.red("rollback failed"))
              }
            case None => Console.printLine(s"no checkpoint #$n (see /rollback list)")
        }.andThen(true)
      case _ =>
        Console.printLine("usage: /rollback [list | create | <number>]").andThen(true)

  // --- /prompt: inline multi-line compose (portable; no $EDITOR handoff) ------

  private def collectLines(acc: List[String]): List[String] < (Sync & Async) =
    editor.readLine(Style.dim("… ")).map {
      case Absent => acc
      case Present(line) =>
        if line.trim == "." then acc else collectLines(acc :+ line)
    }

  private def doPrompt(initial: String): Boolean < (Sync & Async) =
    Console.printLine(Style.dim("compose — finish with a line containing only '.'")).andThen {
      collectLines(if initial.nonEmpty then List(initial) else Nil).map { lines =>
        val text = lines.mkString("\n").trim
        if text.isEmpty then Console.printLine("(empty — nothing sent)")
        else runTurn(text).map(_ => ())
      }
    }.andThen(true)

  // --- /goal: standing objective injected into every turn's system prompt ----

  private def doGoal(arg: String): Boolean < (Sync & Async) =
    arg.toLowerCase match
      case "" | "show" | "status" =>
        Console.printLine(goal match
          case Present(g) => s"goal: $g"
          case Absent     => "no goal set (use /goal <text>)").andThen(true)
      case "clear" | "off" =>
        goal = Absent
        Console.printLine("goal cleared").andThen(true)
      case _ =>
        goal = Present(arg)
        Console.printLine(s"goal set: $arg").andThen(true)

  // --- /queue: stack prompts to run after the next completed turn ------------

  private def doQueue(arg: String): Boolean < (Sync & Async) =
    arg.toLowerCase match
      case "" =>
        Console.printLine(
          if queued.isEmpty then "queue empty"
          else queued.zipWithIndex.map((p, i) => s"${i + 1}. $p").mkString("\n")
        ).andThen(true)
      case "clear" =>
        queued = Nil
        Console.printLine("queue cleared").andThen(true)
      case _ =>
        queued = queued :+ arg
        Console.printLine(Style.dim(s"queued (${queued.length} pending) — runs after your next message")).andThen(true)

  // --- /moa: mixture of agents — N answers in parallel, then synthesize ------

  private def childAnswer(prompt: String, i: Int): String < (Sync & Async) =
    Sync.defer(java.time.Instant.now()).map { now =>
      val id    = store.newSessionId(now)
      val flag  = new java.util.concurrent.atomic.AtomicBoolean(false)
      val child = new Agent(runtime, toolCtx, store, id, toolCtx.config.maxTurns, flag)
      store.create(SessionMeta(id, Present(s"moa-$i"), "cli", runtime.model, runtime.providerSlug,
          now.toEpochMilli / 1000.0, Absent, toolCtx.cwd.toString, 0, 0, Usage.zero))
        .andThen(SystemPrompt.build(SystemPrompt.Input(toolCtx.config, toolCtx.paths, toolCtx.skills,
          toolCtx.cwd, "cli", runtime.model, runtime.providerSlug, toolNames)))
        .map(system => child.runTurn(Message.user(prompt), system, toolNames, TurnCallbacks()).map(_.finalResponse))
    }

  private def doMoa(prompt: String, n: Int): Boolean < (Sync & Async) =
    val children = (1 to n).toList.map(i => childAnswer(prompt, i))
    Console.printLine(Style.dim(s"· running $n agents in parallel…")).andThen {
      Async.gather(children).map { answers =>
        val combined = answers.toList.zipWithIndex
          .map((a, i) => s"## Answer ${i + 1}\n$a").mkString("\n\n")
        val synth =
          s"$n independent agents answered the request below. Synthesize the single best response, " +
            s"reconciling differences and keeping what is correct.\n\nRequest: $prompt\n\n$combined"
        runTurn(synth).map(_ => ())
      }
    }.andThen(true)

  private def doBranch(name: String): Boolean < (Sync & Async) =
    val hist = agent.history
    Sync.defer(java.time.Instant.now()).map { now =>
      val newId = store.newSessionId(now)
      val title = if name.nonEmpty then name else s"branch of $sess"
      store.create(SessionMeta(newId, Present(title), "cli", runtime.model, runtime.providerSlug,
          now.toEpochMilli / 1000.0, Absent, toolCtx.cwd.toString, hist.length, 0, Usage.zero))
        .andThen(store.rewriteTranscript(newId, hist))
        .andThen {
          agent.resumeSession(newId, hist)
          sess = newId
          Console.printLine(s"branched to $newId ($title)")
        }
    }.andThen(true)

  private def doBg(prompt: String): Boolean < (Sync & Async) =
    Sync.defer(java.time.Instant.now()).map { now =>
      val id    = store.newSessionId(now)
      val flag  = new java.util.concurrent.atomic.AtomicBoolean(false)
      val child = new Agent(runtime, toolCtx, store, id, toolCtx.config.maxTurns, flag)
      store.create(SessionMeta(id, Present(s"bg: ${prompt.take(40)}"), "cli", runtime.model,
          runtime.providerSlug, now.toEpochMilli / 1000.0, Absent, toolCtx.cwd.toString, 0, 0, Usage.zero))
        .andThen(SystemPrompt.build(SystemPrompt.Input(toolCtx.config, toolCtx.paths, toolCtx.skills,
          toolCtx.cwd, "cli", runtime.model, runtime.providerSlug, toolNames)))
        .map { system =>
          BackgroundSessions.put(BackgroundSessions.Job(
            id, prompt, now.toEpochMilli / 1000.0, BackgroundSessions.Status.Running, flag))
          val work = child.runTurn(Message.user(prompt), system, toolNames, TurnCallbacks()).map { res =>
            Sync.defer(BackgroundSessions.finish(id, res.exitReason, res.finalResponse.length))
          }
          Fiber.initUnscoped(work).andThen(Console.printLine(s"started background session $id — /agents to check"))
        }
    }.andThen(true)

  private def doStop(arg: String): Boolean < (Sync & Async) =
    if arg.isEmpty then
      val running = BackgroundSessions.all.filter(_.status == BackgroundSessions.Status.Running)
      running.foreach(j => BackgroundSessions.cancel(j.id))
      Console.printLine(s"stopped ${running.length} background session(s)").andThen(true)
    else
      val ok = BackgroundSessions.cancel(arg)
      Console.printLine(if ok then s"stopping $arg" else s"no background session $arg").andThen(true)

  private def doLoopCmd(arg: String): Boolean < (Sync & Async) =
    val (prompt, times, every) = ReplCommands.parseLoop(arg)
    if prompt.isEmpty then Console.printLine("usage: /loop <prompt> [--times N] [--every S]").andThen(true)
    else doLoop(prompt, times, every, 1).andThen(true)

  private def doLoop(prompt: String, times: Int, every: Int, n: Int): Unit < (Sync & Async) =
    if n > times then Console.printLine(Style.dim(s"· loop complete ($times runs)"))
    else
      Console.printLine(Style.dim(s"· loop $n/$times")).andThen(runTurn(prompt)).map { res =>
        if res.interrupted then Console.printLine(Style.dim("· loop interrupted"))
        else if n >= times then Console.printLine(Style.dim(s"· loop complete ($times runs)"))
        else if every > 0 then Async.sleep(every.seconds).andThen(doLoop(prompt, times, every, n + 1))
        else doLoop(prompt, times, every, n + 1)
      }

  private def doReview(instructions: String): Boolean < (Sync & Async) =
    val hist = agent.history
    if hist.isEmpty then Console.printLine("nothing to review yet").andThen(true)
    else
      Sync.defer(java.time.Instant.now()).map { now =>
        val id       = store.newSessionId(now)
        val flag     = new java.util.concurrent.atomic.AtomicBoolean(false)
        val reviewer = new Agent(runtime, toolCtx, store, id, toolCtx.config.maxTurns, flag)
        reviewer.restore(hist)
        val ask =
          "Review the conversation above as an independent critic: point out bugs, risks, and gaps. " +
            "Do not execute tools." + (if instructions.nonEmpty then s" Focus: $instructions" else "")
        store.create(SessionMeta(id, Present("review"), "cli", runtime.model, runtime.providerSlug,
            now.toEpochMilli / 1000.0, Absent, toolCtx.cwd.toString, hist.length, 0, Usage.zero))
          .andThen(SystemPrompt.build(SystemPrompt.Input(toolCtx.config, toolCtx.paths, toolCtx.skills,
            toolCtx.cwd, "cli", runtime.model, runtime.providerSlug, Nil)))
          .map { system =>
            Console.printLine(Style.dim("· running review subagent…"))
              .andThen(reviewer.runTurn(Message.user(ask), system, Nil, callbacks))
              .andThen(Console.printLine(""))
          }
      }.andThen(true)

  private def helpText: String =
    """commands
      |  /help                    this help
      |  /version | /v            show apollo + model version
      |  /whoami                  show access level
      |  /model [name]            show or switch the model (provider:model or bare id)
      |  /reasoning <level>       none|minimal|low|medium|high|xhigh|max
      |  /reasoning-display       toggle thinking display
      |  /verbose                 toggle tool-progress display
      |session
      |  /status | /history       model, message count, token usage, context %
      |  /usage                   cumulative token usage
      |  /config                  effective configuration summary
      |  /profile                 active profile and home dir
      |  /reset | /new            clear the conversation
      |  /clear                   clear screen + fresh conversation
      |  /redraw                  repaint the banner
      |  /title <name>            name the current session
      |  /compress | /compact     force context compaction before the next call
      |  /save [file.md]          write the transcript to Markdown (default <session>.md)
      |  /prompt | /compose [text]   compose a multi-line message (end with '.')
      |  /retry                   re-run the last user turn
      |  /copy                    copy the last reply to the clipboard
      |  /image <path>            attach an image to your next message
      |  /sessions                list previous sessions
      |  /resume <id|latest>      resume a previous session
      |  /branch | /fork [name]   fork this session into a new one
      |work
      |  /plan <task>             write a plan without executing
      |  /init [notes]            generate/update AGENTS.md from a repo scan
      |  /diff [args]             git diff of the working tree
      |  /loop <prompt> [--times N] [--every S]   re-run a prompt N times
      |  /bg <prompt>             run a prompt in a background session
      |  /agents | /tasks         list background sessions
      |  /stop [id]               cancel a background session (all if no id)
      |  /review [focus]          independent subagent review of the conversation
      |  /goal [text|show|clear]  standing objective injected into every turn
      |  /queue [prompt|clear]    stack prompts to run after the next turn
      |  /moa <prompt>            mixture-of-agents: 3 answers in parallel, then synthesize
      |  /learn <what>            capture something as a reusable skill
      |  /worktree [list|new [name]|prune]   manage git worktrees
      |  /snapshot [create|list|restore <id>|prune]   snapshot session state
      |  /rollback [list|create|<number>]     git working-tree checkpoints
      |tools & services
      |  /tools                   list active tools
      |  /skills                  list available skills
      |  /reload-skills           re-scan installed skills
      |  /mcp                     MCP server status and tools
      |  /cron                    list scheduled jobs
      |  /memory                  show recorded memory
      |approvals
      |  /yolo                    toggle dangerous-command approval bypass
      |  /approvals [manual|off]  show or set the approval mode
      |  /quit | /exit            exit""".stripMargin

  private def doResume(target: String): Boolean < (Sync & Async) =
    store.find(target).map {
      case Present(meta) =>
        store.loadTranscript(meta.id).map { msgs =>
          agent.resumeSession(meta.id, msgs)
          sess = meta.id
          Console.printLine(s"resumed ${meta.id} — ${msgs.length} messages")
        }
      case Absent =>
        Console.printLine(s"no session matching '$target'")
    }.andThen(true)

  private def doSave(arg: String): Boolean < (Sync & Async) =
    val fileName = if arg.nonEmpty then arg else s"$sess.md"
    val path     = toolCtx.cwd.resolve(fileName)
    val md       = ReplCommands.toMarkdown(sess, agent.history)
    Fs.writeString(path, md)
      .andThen(Console.printLine(s"saved ${agent.history.length} messages to $path"))
      .andThen(true)

  private def doRetry: Boolean < (Sync & Async) =
    val h   = agent.history
    val idx = h.lastIndexWhere(_.role == Role.User)
    if idx < 0 then Console.printLine("nothing to retry").andThen(true)
    else
      val trimmed = h.take(idx)
      val text    = ReplCommands.messageText(h(idx))
      agent.restore(trimmed)
      store.rewriteTranscript(sess, trimmed).andThen(runTurn(text)).andThen(true)

  private def doCopy: Boolean < (Sync & Async) =
    val text = agent.history.reverse.collectFirst {
      case m if m.role == Role.Assistant => ReplCommands.messageText(m)
    }.getOrElse("")
    if text.isEmpty then Console.printLine("nothing to copy").andThen(true)
    else
      ReplCommands.clipboardCommand(java.lang.System.getProperty("os.name", "")) match
        case None => Console.printLine("clipboard not supported on this OS").andThen(true)
        case Some(clip) =>
          val tmp = toolCtx.paths.home.resolve(".apollo-clip")
          Fs.writeString(tmp, text).andThen {
            Abort.run[CommandException](Command("sh", "-c", s"""$clip < "${tmp.toString}"""").text).map { res =>
              Fs.delete(tmp).andThen {
                res match
                  case Result.Success(_) => Console.printLine(Style.dim(s"copied ${text.length} chars to clipboard"))
                  case _                 => Console.printLine(Style.red("clipboard copy failed (is a clipboard tool installed?)"))
              }
            }
          }.andThen(true)

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

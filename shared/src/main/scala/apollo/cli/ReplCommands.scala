package apollo.cli

import apollo.core.{Message, Content, Role, Usage}
import apollo.session.SessionMeta
import apollo.cron.CronJob
import apollo.mcp.McpManager
import kyo.{Maybe, Present, Absent}

/** Pure rendering/logic for the REPL slash commands, factored out of `Repl` so
  * it can be unit-tested on both JVM and Native without an Agent, editor, or
  * live provider. `Repl.handleSlash` stays thin glue over these functions.
  */
object ReplCommands:

  /** Concatenated text blocks of a message (thinking/tool blocks excluded). */
  def messageText(m: Message): String =
    m.content.collect { case Content.Text(t) => t }.mkString("\n").trim

  /** Epoch-seconds → ISO-8601 (Instant.toString is Native-safe; no formatter). */
  private def ts(epoch: Double): String =
    try java.time.Instant.ofEpochSecond(epoch.toLong).toString
    catch case _: Throwable => epoch.toLong.toString

  def formatUsage(u: Usage): String =
    s"${u.inputTokens} in / ${u.outputTokens} out" +
      (if u.cacheReadTokens > 0 then s" / ${u.cacheReadTokens} cache-read" else "") +
      (if u.reasoningTokens > 0 then s" / ${u.reasoningTokens} reasoning" else "")

  /** Body of `/status` and `/history` — real token + context figures. */
  def statusLines(
      sessionId: String, model: String, provider: String,
      msgCount: Int, apiCalls: Int, usage: Usage,
      lastPromptTokens: Long, contextWindow: Int
  ): String =
    val pct = if contextWindow > 0 then (lastPromptTokens.toDouble / contextWindow * 100).round else 0L
    List(
      s"session   $sessionId",
      s"model     $model ($provider)",
      s"messages  $msgCount  ·  api calls  $apiCalls",
      s"tokens    ${formatUsage(usage)}  ·  total ${usage.total}",
      s"context   ~$lastPromptTokens / $contextWindow last prompt (${pct}%)"
    ).mkString("\n")

  def formatSessions(metas: List[SessionMeta], limit: Int = 20): String =
    if metas.isEmpty then "no saved sessions"
    else
      val shown = metas.take(limit).map { m =>
        val title = m.title.getOrElse("(untitled)")
        s"${m.id}  ${ts(m.startedAt)}  ${m.messageCount} msgs  ${m.model}  $title"
      }.mkString("\n")
      if metas.length > limit then s"$shown\n… ${metas.length - limit} more (use /resume <id>)" else shown

  def formatCron(jobs: List[CronJob]): String =
    if jobs.isEmpty then "no scheduled jobs"
    else jobs.map { j =>
      val next = j.nextRunAt match { case Present(t) => ts(t); case Absent => "-" }
      val flag = if j.enabled then j.state else "disabled"
      s"${j.id}  [$flag]  ${j.scheduleDisplay}  next=$next  ${j.name}"
    }.mkString("\n")

  def formatMcp(statuses: List[McpManager.ServerStatus], toolsets: Map[String, List[String]]): String =
    if statuses.isEmpty then "no MCP servers configured"
    else statuses.map { s =>
      val name = s.config.name
      val st = s.state match
        case McpManager.State.Connecting     => "connecting"
        case McpManager.State.Connected(n)   => s"connected · $n tools"
        case McpManager.State.Reconnecting   => "reconnecting"
        case McpManager.State.Failed(r)      => s"failed: $r"
        case McpManager.State.Parked(r)      => s"parked: $r"
        case McpManager.State.Unsupported(r) => s"unsupported: $r"
      val tools    = toolsets.getOrElse(s"mcp-$name", Nil)
      val toolLine = if tools.isEmpty then "" else "\n    " + tools.sorted.mkString(", ")
      s"$name  [$st]$toolLine"
    }.mkString("\n")

  /** Aligned key/value block (used by `/config`). */
  def formatKeyValues(pairs: List[(String, String)]): String =
    val w = pairs.map(_._1.length).maxOption.getOrElse(0)
    pairs.map { case (k, v) => s"${k.padTo(w, ' ')}  $v" }.mkString("\n")

  /** Render a transcript to Markdown for `/save`. */
  def toMarkdown(sessionId: String, messages: List[Message]): String =
    val header = s"# apollo session $sessionId\n"
    val body = messages.map { m =>
      val role = m.role match
        case Role.System    => "System"
        case Role.User      => "User"
        case Role.Assistant => "Assistant"
        case Role.Tool      => "Tool"
      val parts = m.content.map {
        case Content.Text(t)             => t
        case Content.Thinking(t, _)      => s"_(thinking)_\n\n$t"
        case Content.ToolUse(_, n, a)    => s"`→ $n($a)`"
        case Content.ToolResult(_, o, e) => s"```\n${if e then "[error] " else ""}$o\n```"
        case Content.Image(mt, _)        => s"_(image: $mt)_"
      }.mkString("\n\n")
      s"## $role\n\n$parts"
    }.mkString("\n\n")
    s"$header\n$body\n"

  /** Best-effort profile name from the home path layout (`profiles/<name>`). */
  def profileName(home: java.nio.file.Path): String =
    val parent = Option(home.getParent).flatMap(p => Option(p.getFileName)).map(_.toString)
    if parent.contains("profiles") then Option(home.getFileName).map(_.toString).getOrElse("default")
    else "default"

  def formatJobs(jobs: List[BackgroundSessions.Job]): String =
    if jobs.isEmpty then "no background sessions"
    else jobs.map { j =>
      val st = j.status match
        case BackgroundSessions.Status.Running    => "running"
        case BackgroundSessions.Status.Done(r, n) => s"done · $r · $n chars"
        case BackgroundSessions.Status.Failed(m)  => s"failed: $m"
        case BackgroundSessions.Status.Cancelled  => "cancelled"
      s"${j.id}  [$st]  ${j.prompt.take(50)}"
    }.mkString("\n")

  /** Parse `/loop` args: "<prompt> [--times N] [--every S]" → (prompt, times, everySeconds).
    * `times` defaults to 3 (bounded ≥1); `every` defaults to 0 (no delay). */
  def parseLoop(arg: String): (String, Int, Int) =
    var times = 3
    var every = 0
    val rest  = scala.collection.mutable.ListBuffer[String]()
    @annotation.tailrec def go(ts: List[String]): Unit = ts match
      case "--times" :: n :: r => times = n.toIntOption.getOrElse(times); go(r)
      case "--every" :: n :: r => every = n.toIntOption.getOrElse(every); go(r)
      case x :: r              => rest += x; go(r)
      case Nil                 => ()
    go(arg.split("\\s+").toList.filter(_.nonEmpty))
    (rest.mkString(" "), math.max(1, times), math.max(0, every))

  /** Split accumulated stream text into complete lines (each terminated by a
    * newline in the input) plus the trailing remainder. Used by async-input
    * mode to feed whole lines to `printAbove` (which is line-oriented). */
  def takeCompleteLines(s: String): (List[String], String) =
    val idx = s.lastIndexOf('\n')
    if idx < 0 then (Nil, s)
    else (s.substring(0, idx).split("\n", -1).toList, s.substring(idx + 1))

  /** Parse a `/heartbeat` interval ("30s", "5m", "2h", or bare seconds) → seconds. */
  def parseInterval(s: String): Option[Int] =
    val t = s.trim.toLowerCase
    val (numStr, mult) =
      if t.endsWith("s") then (t.dropRight(1), 1)
      else if t.endsWith("m") then (t.dropRight(1), 60)
      else if t.endsWith("h") then (t.dropRight(1), 3600)
      else (t, 1)
    numStr.toIntOption.filter(_ > 0).map(_ * mult)

  /** Parse `git for-each-ref ... %(refname)|%(creatordate:iso)` into (ref, date). */
  def parseCheckpoints(out: String): List[(String, String)] =
    out.linesIterator.map(_.trim).filter(_.nonEmpty).flatMap { line =>
      line.split("\\|", 2) match
        case Array(ref, date) => Some((ref, date))
        case Array(ref)       => Some((ref, ""))
        case _                => None
    }.toList

  /** Numbered, newest-first checkpoint listing for `/rollback`. */
  def formatCheckpoints(cks: List[(String, String)]): String =
    if cks.isEmpty then "no checkpoints (create one with /rollback create)"
    else cks.zipWithIndex.map { case ((ref, date), i) =>
      s"${i + 1}  ${ref.stripPrefix("refs/apollo/ckpt/")}  $date"
    }.mkString("\n")

  /** Image media type from a file extension; None if unsupported. */
  def imageMediaType(path: String): Option[String] =
    val lower = path.toLowerCase
    if lower.endsWith(".png") then Some("image/png")
    else if lower.endsWith(".jpg") || lower.endsWith(".jpeg") then Some("image/jpeg")
    else if lower.endsWith(".gif") then Some("image/gif")
    else if lower.endsWith(".webp") then Some("image/webp")
    else None

  /** Single-quote a string for safe use inside `sh -c`. */
  def shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

  /** Map `/worktree` args to a git command; None → show usage. */
  def worktreeCommand(arg: String): Option[String] =
    arg.split("\\s+").toList.filter(_.nonEmpty) match
      case Nil | ("list" :: _) => Some("git worktree list")
      case "new" :: rest       => Some(s"git worktree add ${shellQuote(rest.headOption.getOrElse("apollo-worktree"))}")
      case "prune" :: rest     => Some("git worktree prune" + (if rest.contains("--dry-run") then " -n" else ""))
      case _                   => None

  /** Shell fragment that copies stdin/a file to the OS clipboard; None if the
    * OS is unknown. Composed into `sh -c "<cmd> < file"`.
    */
  def clipboardCommand(osName: String): Option[String] =
    val os = osName.toLowerCase
    if os.contains("mac") || os.contains("darwin") then Some("pbcopy")
    else if os.contains("win") then Some("clip")
    else if os.contains("nux") || os.contains("nix") then
      Some("wl-copy 2>/dev/null || xclip -selection clipboard 2>/dev/null || xsel -b 2>/dev/null")
    else None

  // --- /blueprint: automation templates that create cron jobs ---------------

  /** A named automation template: a prompt (with `{slot}` placeholders) run on a
    * schedule. `slots` provides defaults, overridable as `k=v` args. */
  final case class Blueprint(name: String, description: String, template: String,
                             schedule: String, slots: Map[String, String])

  val blueprints: List[Blueprint] = List(
    Blueprint("daily-summary", "Daily progress summary",
      "Summarize progress on {topic} and list the next steps.", "every 24h", Map("topic" -> "the project")),
    Blueprint("hourly-check", "Hourly check of a target",
      "Check {target} and report anything that needs attention.", "every 1h", Map("target" -> "the build")),
    Blueprint("standup", "Brief standup",
      "Give a brief standup: what is done, what is in progress, and what is blocked.", "every 24h", Map.empty)
  )

  def parseSlots(tokens: List[String]): Map[String, String] =
    tokens.flatMap(t => t.split("=", 2) match { case Array(k, v) => Some(k.trim -> v.trim); case _ => None }).toMap

  def renderTemplate(template: String, slots: Map[String, String]): String =
    slots.foldLeft(template) { case (acc, (k, v)) => acc.replace("{" + k + "}", v) }

  def formatBlueprints(bs: List[Blueprint]): String =
    if bs.isEmpty then "no blueprints"
    else bs.map(b => s"${b.name}  —  ${b.description}  (${b.schedule})").mkString("\n")

  // --- /kanban: a local board (columns of cards over a text file) -----------

  val kanbanColumns: List[String] = List("todo", "doing", "done")

  final case class KanbanCard(id: String, col: String, text: String)

  def renderBoardFile(cards: List[KanbanCard]): String =
    cards.map(c => s"${c.col}\t${c.id}\t${c.text.replace('\t', ' ').replace('\n', ' ')}").mkString("\n")

  def parseBoard(s: String): List[KanbanCard] =
    s.linesIterator.map(_.trim).filter(_.nonEmpty).flatMap { line =>
      line.split("\t", 3) match
        case Array(col, id, text) => Some(KanbanCard(id, col, text))
        case _                    => None
    }.toList

  def renderBoard(cards: List[KanbanCard]): String =
    kanbanColumns.map { col =>
      val items = cards.filter(_.col == col)
      val body  = if items.isEmpty then "  (empty)" else items.map(c => s"  [${c.id}] ${c.text}").mkString("\n")
      s"$col:\n$body"
    }.mkString("\n")

  // --- command catalog: the single source of truth for /help and completion --

  /** One REPL slash command: primary name, aliases, an argument hint, a one-line
    * summary, and the help category it belongs to. */
  final case class CommandInfo(name: String, aliases: List[String], arg: String,
                               summary: String, category: String):
    def names: List[String] = name :: aliases
    /** "/name | /alias …" for display. */
    def label: String = names.map("/" + _).mkString(" | ")

  /** Category order for `/help`. */
  private val categoryOrder: List[String] =
    List("commands", "session", "work", "tools & services", "approvals")

  val commandCatalog: List[CommandInfo] = List(
    CommandInfo("help", Nil, "", "this help", "commands"),
    CommandInfo("version", List("v"), "", "show apollo + model version", "commands"),
    CommandInfo("whoami", Nil, "", "show access level", "commands"),
    CommandInfo("model", Nil, "[name]", "show or switch the model (provider:model or bare id)", "commands"),
    CommandInfo("reasoning", Nil, "<level>", "none|minimal|low|medium|high|xhigh|max", "commands"),
    CommandInfo("reasoning-display", Nil, "", "toggle thinking display", "commands"),
    CommandInfo("verbose", Nil, "", "toggle tool-progress display", "commands"),
    CommandInfo("status", List("history"), "", "model, message count, token usage, context %", "session"),
    CommandInfo("usage", Nil, "", "cumulative token usage", "session"),
    CommandInfo("config", Nil, "", "effective configuration summary", "session"),
    CommandInfo("profile", Nil, "", "active profile and home dir", "session"),
    CommandInfo("reset", List("new"), "", "clear the conversation", "session"),
    CommandInfo("clear", Nil, "", "clear screen + fresh conversation", "session"),
    CommandInfo("redraw", Nil, "", "repaint the banner", "session"),
    CommandInfo("title", Nil, "<name>", "name the current session", "session"),
    CommandInfo("compress", List("compact"), "", "force context compaction before the next call", "session"),
    CommandInfo("save", Nil, "[file.md]", "write the transcript to Markdown", "session"),
    CommandInfo("prompt", List("compose"), "[text]", "compose a multi-line message (end with '.')", "session"),
    CommandInfo("retry", Nil, "", "re-run the last user turn", "session"),
    CommandInfo("copy", Nil, "", "copy the last reply to the clipboard", "session"),
    CommandInfo("image", Nil, "<path>", "attach an image to your next message", "session"),
    CommandInfo("sessions", Nil, "", "list previous sessions", "session"),
    CommandInfo("resume", Nil, "<id|latest>", "resume a previous session", "session"),
    CommandInfo("branch", List("fork"), "[name]", "fork this session into a new one", "session"),
    CommandInfo("plan", Nil, "<task>", "write a plan without executing", "work"),
    CommandInfo("init", Nil, "[notes]", "generate/update AGENTS.md from a repo scan", "work"),
    CommandInfo("diff", Nil, "[args]", "git diff of the working tree", "work"),
    CommandInfo("loop", List("proactive"), "<prompt> [--times N] [--every S]", "re-run a prompt N times", "work"),
    CommandInfo("bg", Nil, "<prompt>", "run a prompt in a background session", "work"),
    CommandInfo("agents", List("tasks"), "", "list background sessions", "work"),
    CommandInfo("stop", Nil, "[id]", "cancel a background session (all if no id)", "work"),
    CommandInfo("review", Nil, "[focus]", "independent subagent review of the conversation", "work"),
    CommandInfo("goal", Nil, "[text|show|clear]", "standing objective injected into every turn", "work"),
    CommandInfo("queue", Nil, "[prompt|clear]", "stack prompts to run after the next turn", "work"),
    CommandInfo("moa", Nil, "<prompt>", "mixture-of-agents: 3 answers in parallel, then synthesize", "work"),
    CommandInfo("learn", Nil, "<what>", "capture something as a reusable skill", "work"),
    CommandInfo("heartbeat", List("hb"), "[every <interval> <prompt>|status|pause|resume|clear]", "recurring idle prompt", "work"),
    CommandInfo("steer", Nil, "<message>", "inject guidance after the next tool call", "work"),
    CommandInfo("blueprint", List("bp"), "[name [k=v…]]", "create a cron job from an automation template", "work"),
    CommandInfo("kanban", Nil, "[show|add <col> <text>|move <id> <col>|rm <id>]", "local task board", "work"),
    CommandInfo("curator", Nil, "[status|archive <name>|restore <name>]", "skill maintenance", "work"),
    CommandInfo("handoff", Nil, "<telegram|discord|slack>", "continue this session via a running gateway bot", "work"),
    CommandInfo("worktree", Nil, "[list|new [name]|prune]", "manage git worktrees", "work"),
    CommandInfo("snapshot", List("snap"), "[create|list|restore <id>|prune]", "snapshot session state", "work"),
    CommandInfo("rollback", Nil, "[list|create|<number>]", "git working-tree checkpoints", "work"),
    CommandInfo("tools", Nil, "", "list active tools", "tools & services"),
    CommandInfo("skills", Nil, "", "list available skills", "tools & services"),
    CommandInfo("reload-skills", Nil, "", "re-scan installed skills", "tools & services"),
    CommandInfo("mcp", Nil, "", "MCP server status and tools", "tools & services"),
    CommandInfo("cron", Nil, "", "list scheduled jobs", "tools & services"),
    CommandInfo("memory", Nil, "", "show recorded memory", "tools & services"),
    CommandInfo("yolo", Nil, "", "toggle dangerous-command approval bypass", "approvals"),
    CommandInfo("approvals", Nil, "[manual|off]", "show or set the approval mode", "approvals"),
    CommandInfo("quit", List("exit", "q"), "", "exit", "approvals")
  )

  /** Commands whose name or an alias begins with the typed token (the text after
    * `/`, before any space). A bare `/` returns everything. Once a space has been
    * typed (args started) or the line isn't a slash line, returns Nil. */
  def completeSlash(line: String): List[CommandInfo] =
    if !line.startsWith("/") then Nil
    else
      val rest = line.drop(1)
      if rest.contains(' ') then Nil
      else
        val token = rest.toLowerCase
        commandCatalog.filter(c => c.names.exists(_.toLowerCase.startsWith(token)))

  /** A compact menu of matches for the live completion display. */
  def formatMenu(matches: List[CommandInfo], max: Int = 8): List[String] =
    val shown = matches.take(max).map(c => s"  ${("/" + c.name).padTo(18, ' ')} ${c.summary}")
    if matches.length > max then shown :+ s"  … ${matches.length - max} more" else shown

  /** `/help` text, generated from the catalog so it can never drift from the
    * actual command set. */
  def helpText: String =
    val byCat = commandCatalog.groupBy(_.category)
    val blocks = categoryOrder.flatMap { cat =>
      byCat.get(cat).map { cmds =>
        val lines = cmds.map { c =>
          val left = (c.label + (if c.arg.isEmpty then "" else s" ${c.arg}")).padTo(40, ' ')
          s"  $left ${c.summary}"
        }
        (cat :: lines).mkString("\n")
      }
    }
    blocks.mkString("\n")
end ReplCommands

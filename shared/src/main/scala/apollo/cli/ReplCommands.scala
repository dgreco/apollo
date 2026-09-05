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
end ReplCommands

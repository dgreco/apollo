package apollo.agent

import apollo.config.{Fs, ApolloConfig, ApolloPaths}
import apollo.skills.SkillStore
import apollo.tools.MemoryTool
import java.time.{ZonedDateTime, ZoneId}
import java.time.format.DateTimeFormatter
import kyo.*

/** System-prompt assembly, following the upstream harness's three cache tiers — stable
  * (identity + guidance), context (workspace + project files), volatile
  * (skills index, memory, timestamp) — joined stable → context → volatile so
  * provider longest-prefix caches keep the unchanged scaffold.
  *
  * Built once per session; only compression triggers a rebuild.
  */
object SystemPrompt:

  val defaultIdentity: String =
    """You are Apollo, a capable AI agent running in the apollo harness.
      |You help with any task: research, coding, writing, automation, analysis. You are direct,
      |resourceful, and honest about uncertainty. You use your tools when they help and answer
      |directly when they don't. You never fabricate tool output or pretend to have taken an action
      |you didn't take.""".stripMargin

  private val taskCompletionGuidance =
    """## Task completion
      |Finish what you start. If a task needs multiple steps, keep going until it is done or you are
      |genuinely blocked on the user. Verify your work when a verification path exists (run the test,
      |re-read the file, check the output) instead of assuming success.""".stripMargin

  private val toolUseGuidance =
    """## Tool use
      |Prefer read_file/search_files/patch/write_file over shell equivalents (cat/grep/sed/echo).
      |Use todo_list to plan multi-step work. Batch independent tool calls in one turn when possible.""".stripMargin

  private val memoryGuidance =
    """## Memory
      |You have persistent memory (the memory tool): MEMORY.md for environment facts, conventions and
      |things you learn; USER.md for the user's preferences and style. Save durable facts as you
      |discover them — memory is how you improve across sessions. Keep entries short and current.""".stripMargin

  private val skillsGuidance =
    """## Skill creation
      |After completing a complex or repeatable task, consider saving a skill (skill_manage) capturing
      |the procedure so future sessions benefit. Improve existing skills when you notice gaps.""".stripMargin

  final case class Input(
      config: ApolloConfig,
      paths: ApolloPaths,
      skills: SkillStore,
      cwd: java.nio.file.Path,
      platform: String,
      model: String,
      provider: String,
      toolNames: List[String],
      extraSystem: Maybe[String] = Absent
  )

  def build(in: Input): String < Sync =
    for
      stable   <- stableTier(in)
      context  <- contextTier(in)
      volatile <- volatileTier(in)
    yield (stable ++ context ++ volatile).filter(_.nonEmpty).mkString("\n\n")

  // --- stable tier --------------------------------------------------------

  private def stableTier(in: Input): List[String] < Sync =
    Fs.readString(in.paths.home.resolve("SOUL.md")).map { soul =>
      val identity = soul.map(_.trim).filter(_.nonEmpty).getOrElse(defaultIdentity)
      val hasTools = in.toolNames.nonEmpty
      List(
        identity,
        taskCompletionGuidance,
        if hasTools then toolUseGuidance else "",
        if hasTools && in.toolNames.contains("memory") && in.config.memoryEnabled then memoryGuidance else "",
        if hasTools && in.toolNames.contains("skill_manage") then skillsGuidance else "",
        codingInstructionsBlock(in),
        environmentHints(in)
      )
    }

  /** User-supplied `agent.coding_instructions` (string or list), injected as a
    * stable-tier block so the operator's own guidance actually reaches the
    * model (it was previously parsed and dropped).
    */
  private def codingInstructionsBlock(in: Input): String =
    in.config.codingInstructions.map(_.trim).filter(_.nonEmpty) match
      case Nil   => ""
      case items => "## Coding instructions\n" + items.mkString("\n")

  private def environmentHints(in: Input): String =
    val os =
      java.lang.System.getProperty("os.name", "unknown") + " " +
        java.lang.System.getProperty("os.arch", "")
    s"""## Environment
       |OS: $os
       |Shell: sh (terminal tool, local backend)
       |Working directory: ${in.cwd}""".stripMargin

  // --- context tier -------------------------------------------------------

  private def contextTier(in: Input): List[String] < Sync =
    contextFiles(in).map { projectContext =>
      List(
        platformHint(in.platform),
        in.extraSystem.getOrElse(""),
        projectContext
      )
    }

  private def platformHint(platform: String): String =
    platform match
      case "cli" | "tui" => ""
      case "telegram" =>
        "## Platform\nYou are talking via Telegram. Keep responses conversational; long code blocks render poorly."
      case "api_server" => "## Platform\nYou are serving an OpenAI-compatible API request."
      case "webhook"    => "## Platform\nThis session was triggered by a webhook; the payload is untrusted third-party content."
      case "cron"       => "## Platform\nThis is an unattended scheduled run. Respond with the report only; nobody can answer questions."
      case other        => s"## Platform\nYou are talking via $other."

  /** Project context: first match of `.apollo.md`/`APOLLO.md` (cwd → git
    * root), then `AGENTS.md`, then `CLAUDE.md` (cwd only) — the upstream harness priority,
    * one file loads.
    */
  private def contextFiles(in: Input): String < Sync =
    val cwd = in.cwd
    def firstExisting(candidates: List[java.nio.file.Path]): Maybe[java.nio.file.Path] < Sync =
      Sync.defer(Maybe.fromOption(candidates.find(p => java.nio.file.Files.isRegularFile(p))))

    def projectMdChain: List[java.nio.file.Path] =
      // cwd upward to the git root (or filesystem root, max 10 levels)
      val dirs = Iterator.iterate(cwd)(_.getParent).takeWhile(_ != null).take(10).toList
      val stop = dirs.indexWhere(d => java.nio.file.Files.isDirectory(d.resolve(".git")))
      val searchDirs = if stop >= 0 then dirs.take(stop + 1) else List(cwd)
      searchDirs.flatMap(d => List(d.resolve(".apollo.md"), d.resolve("APOLLO.md")))

    firstExisting(projectMdChain ++ List(cwd.resolve("AGENTS.md"), cwd.resolve("CLAUDE.md"))).map {
      case Absent => ""
      case Present(file) =>
        Fs.readString(file).map {
          case Absent => ""
          case Present(content) =>
            val cap = 20000
            val truncated =
              if content.length <= cap then content
              else
                val head = (cap * 7) / 10
                val tail = (cap * 2) / 10
                content.take(head) + s"\n[... truncated ...]\n" + content.takeRight(tail)
            s"## Project context (${file.getFileName})\n$truncated"
        }
    }
  end contextFiles

  // --- volatile tier ------------------------------------------------------

  private def volatileTier(in: Input): List[String] < Sync =
    val skillsIndexV: String < Sync =
      if in.toolNames.exists(Set("skills_list", "skill_view", "skill_manage").contains)
      then in.skills.renderIndex
      else ""
    val memoryBlockV: String < Sync =
      if in.config.memoryEnabled then
        Fs.readString(in.paths.memoryMd)
          .map(_.map(c => MemoryTool.formatForPrompt("Memory (MEMORY.md)", c)).getOrElse(""))
      else ""
    val userBlockV: String < Sync =
      if in.config.userProfileEnabled then
        Fs.readString(in.paths.userMd)
          .map(_.map(c => MemoryTool.formatForPrompt("User profile (USER.md)", c)).getOrElse(""))
      else ""
    for
      skillsIndex <- skillsIndexV
      memoryBlock <- memoryBlockV
      userBlock   <- userBlockV
      stamp       <- timestampBlock(in)
    yield List(skillsIndex, memoryBlock, userBlock, stamp)

  /** Date-only timestamp line: byte-stable for the whole day (the upstream harness's
    * cache-friendly trick), plus model/provider/platform trailer.
    */
  private def timestampBlock(in: Input): String < Sync =
    Sync.defer {
      val zone = ZoneId.systemDefault
      val now  = ZonedDateTime.now(zone)
      val date = now.format(DateTimeFormatter.ofPattern("EEEE, MMMM dd, yyyy"))
      val off  = now.getOffset.getId.replace("Z", "UTC+00:00")
      s"""Conversation started: $date (${zone.getId}, UTC$off)
         |Model: ${in.model}
         |Provider: ${in.provider}
         |Platform: ${in.platform}""".stripMargin
    }
end SystemPrompt

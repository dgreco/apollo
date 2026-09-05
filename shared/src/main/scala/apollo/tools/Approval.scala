package apollo.tools

import apollo.config.{Fs, ApolloConfig, ApolloPaths}
import kyo.*
import scala.util.matching.Regex

/** Shell-command approval gating, mirroring the upstream order of checks:
  * floors (hardline patterns + operator deny globs, applied even under
  * yolo) → yolo / `approvals.mode: off` → session approvals → permanent
  * allowlist → unattended-context policy → dangerous-command detection →
  * human decision.
  */
final class ApprovalService(
    config: ApolloConfig,
    paths: ApolloPaths,
    platform: String,
    oneShot: Boolean,
    yoloFlag: Boolean,
    singleQuery: Boolean = false
):
  private val sessionApproved = new java.util.concurrent.ConcurrentHashMap[String, Boolean]()

  private def yolo: Boolean =
    yoloFlag || config.env.getBool("APOLLO_YOLO_MODE").getOrElse(false)

  private val unattendedPlatforms = Set("webhook", "api_server", "msgraph_webhook")

  private def allowlistFile = paths.home.resolve("scala-approved-commands.json")

  def check(command: String, ui: ToolUi): Result[String, Unit] < (Sync & Async) =
    // 1. Floors: run before yolo so `approvals.deny` blocks unconditionally.
    if hardlineBlock(command) then
      Result.fail("command blocked: matches a hardline destructive pattern. Do not retry it.")
    else
      denyGlobHit(command) match
        case Present(glob) =>
          Result.fail(s"command blocked by approvals.deny pattern '$glob'. Do not retry it.")
        case Absent =>
          if yolo || config.approvalMode == "off" then Result.succeed(())
          else if !Detection.isDangerous(command) then Result.succeed(())
          else checkDangerous(command, ui)

  private def checkDangerous(command: String, ui: ToolUi): Result[String, Unit] < (Sync & Async) =
    val key = Detection.patternKey(command)
    if sessionApproved.containsKey(key) then Result.succeed(())
    else
      loadPermanent.map { permanent =>
        if permanent.contains(key) then Result.succeed(())
        else if oneShot then
          // `-z` one-shot runs bypass approvals, matching the upstream harness.
          Result.succeed(())
        else if singleQuery then resolveUnattended(config.singleQueryApprovalMode)
        else if platform == "cron" then resolveUnattended(config.cronApprovalMode)
        else if unattendedPlatforms.contains(platform) then resolveUnattended(config.unattendedApprovalMode)
        else interactiveApproval(command, key, permanent, ui)
      }
  end checkDangerous

  /** The human once/session/always/deny prompt, bounded by `approvals.timeout`
    * (seconds; ≤0 = wait forever). An unanswered prompt auto-denies so an
    * agent can't hang forever waiting on an absent operator.
    */
  private def interactiveApproval(
      command: String, key: String, permanent: Set[String], ui: ToolUi
  ): Result[String, Unit] < (Sync & Async) =
    val ask: Result[String, Unit] < (Sync & Async) =
      ui.requestApproval(s"Allow this command?\n  $command").map {
        case ApprovalDecision.Once => Result.succeed(())
        case ApprovalDecision.Session =>
          sessionApproved.put(key, true)
          Result.succeed(())
        case ApprovalDecision.Always =>
          sessionApproved.put(key, true)
          savePermanent(permanent + key).map(_ => Result.succeed(()))
        case ApprovalDecision.Deny =>
          Result.fail("command denied by the user. Do not retry it; adjust your approach or ask.")
      }
    val timeout = config.approvalTimeoutSeconds
    if timeout <= 0 then ask
    else
      Abort.run[Timeout](Async.timeout(timeout.seconds)(ask)).map {
        case Result.Success(r) => r
        case _ =>
          Result.fail(s"approval prompt timed out after ${timeout}s (approvals.timeout); " +
            "command auto-denied. Do not retry it.")
      }
  end interactiveApproval

  private def resolveUnattended(mode: String): Result[String, Unit] =
    if mode == "approve" then Result.succeed(())
    else Result.fail("dangerous command auto-denied (unattended session; see approvals.* config)")

  private def denyGlobHit(command: String): Maybe[String] =
    // Match de-obfuscated variants too, so `r\m` / `git st""atus` can't dodge.
    val variants = List(command, command.replace("\\", ""), command.replace("\"\"", "").replace("''", ""))
    Maybe.fromOption(config.approvalDenyGlobs.find { glob =>
      val r = Detection.fnmatch(glob)
      variants.exists(v => r.matches(v))
    })

  private def hardlineBlock(command: String): Boolean =
    Detection.hardline.exists(_.findFirstIn(command).isDefined)

  private def loadPermanent: Set[String] < Sync =
    Fs.readString(allowlistFile).map {
      case Present(text) => text.linesIterator.map(_.trim).filter(_.nonEmpty).toSet
      case Absent        => Set.empty
    }

  private def savePermanent(entries: Set[String]): Unit < Sync =
    Fs.writeStringAtomic(allowlistFile, entries.toList.sorted.mkString("\n"))
end ApprovalService

/** Dangerous / catastrophic command pattern detection. */
object Detection:

  /** Catastrophic patterns that are blocked outright, even under yolo. */
  val hardline: List[Regex] = List(
    """rm\s+(-[a-zA-Z]*\s+)*(/|/\*)\s*$""".r,
    """rm\s+-[a-zA-Z]*[rf][a-zA-Z]*\s+(-[a-zA-Z]*\s+)*(/|/\*)(\s|$)""".r,
    """mkfs(\.[a-z0-9]+)?\s""".r,
    """dd\s+.*of=/dev/(disk|sd|nvme|hd)""".r,
    """:\(\)\s*\{\s*:\|:&\s*\};:""".r,
    """>\s*/dev/(disk|sd|nvme|hd)""".r
  )

  /** Patterns that require approval (not blocked, just gated). */
  private val dangerous: List[Regex] = List(
    """\brm\s+-[a-zA-Z]*[rf]""".r,
    """\bsudo\b""".r,
    """\bchmod\s+(-[a-zA-Z]+\s+)*777""".r,
    """\bchown\s+-R\b""".r,
    """\bgit\s+push\s+.*(--force|-f)\b""".r,
    """\bgit\s+reset\s+--hard""".r,
    """\bgit\s+clean\s+-[a-zA-Z]*f""".r,
    """curl[^|]*\|\s*(ba|z|fi|da)?sh""".r,
    """wget[^|]*\|\s*(ba|z|fi|da)?sh""".r,
    """\bkill\s+(-9\s+)?1\b""".r,
    """\bshutdown\b|\breboot\b|\bhalt\b""".r,
    """\bdd\s+if=""".r,
    """>\s*/etc/""".r,
    """\btruncate\s+-s\s*0""".r,
    """\bDROP\s+(TABLE|DATABASE)\b""".r
  )

  def isDangerous(command: String): Boolean =
    dangerous.exists(_.findFirstIn(command).isDefined)

  /** Stable key used for session/permanent approval memory: the matched
    * pattern class rather than the exact command string, so `rm -rf a` and
    * `rm -rf b` share one grant.
    */
  def patternKey(command: String): String =
    dangerous.find(_.findFirstIn(command).isDefined).map(_.regex)
      .getOrElse(command.take(120))

  /** fnmatch-style glob → anchored regex. */
  def fnmatch(glob: String): Regex =
    ("(?s)^" + glob.flatMap {
      case '*'  => ".*"
      case '?'  => "."
      case '['  => "["
      case ']'  => "]"
      case c    => Regex.quote(c.toString)
    } + "$").r
end Detection

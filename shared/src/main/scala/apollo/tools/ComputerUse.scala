package apollo.tools

import kyo.*
import kyo.Structure.Value
import apollo.util.Jx.*

/** computer_use — desktop control (screen / mouse / keyboard), mirroring
  * Hermes's `computer_use` tool surface. Hermes drives the external `cua-driver`
  * binary; apollo uses native macOS subprocesses instead (`screencapture` for
  * screenshots, `cliclick` for mouse/keyboard) — the same capability without a
  * bespoke driver protocol. macOS-only; opt-in via `computer_use.enabled`
  * (needs Screen Recording + Accessibility permissions; `cliclick` on PATH). */
object ComputerUse:

  // --- pure arg builders ---------------------------------------------------

  def screencaptureArgs(path: String): List[String] = List("screencapture", "-x", path)

  /** A single cliclick directive for an action, or an error string. */
  def cliclickDirective(action: String, x: Maybe[Long], y: Maybe[Long], text: Maybe[String], key: Maybe[String]): Either[String, String] =
    def xy: Either[String, String] = (x, y) match
      case (Present(px), Present(py)) => Right(s"$px,$py")
      case _                         => Left(s"$action requires x and y")
    action match
      case "move"         => xy.map(p => s"m:$p")
      case "click"        => xy.map(p => s"c:$p")
      case "double_click" => xy.map(p => s"dc:$p")
      case "right_click"  => xy.map(p => s"rc:$p")
      case "type"         => text match { case Present(t) => Right(s"t:$t"); case Absent => Left("type requires text") }
      case "key"          => key match { case Present(k) => Right(s"kp:$k"); case Absent => Left("key requires 'key'") }
      case other          => Left(s"unknown action '$other'")

  val entries: List[ToolEntry] = List(
    ToolEntry(
      name = "computer_use",
      toolset = "computer_use",
      description = "Control the desktop (macOS): screenshot, move, click, double_click, right_click, type, key. " +
        "Requires computer_use.enabled and (for input) the `cliclick` CLI.",
      parametersJson = """{"type":"object","properties":{
        "action":{"type":"string","enum":["screenshot","move","click","double_click","right_click","type","key"]},
        "x":{"type":"integer"},"y":{"type":"integer"},
        "text":{"type":"string","description":"Text to type"},
        "key":{"type":"string","description":"Key name for the key action (e.g. return, esc)"},
        "filename":{"type":"string","description":"screenshot output name"}
      },"required":["action"]}""".replaceAll("\n\\s*", ""),
      emoji = "🖥️",
      available = _.config.computerUseEnabled,
      handler = handle
    )
  )

  private def handle(args: Value, ctx: ToolContext): ToolOutcome < (Sync & Async) =
    (args / "action").asStr.getOrElse("") match
      case "screenshot" =>
        val name = (args / "filename").asStr.getOrElse(s"screen-${java.util.UUID.randomUUID.toString.take(8)}.png")
        val path = ctx.cwd.resolve(name)
        Abort.run[CommandException](Command(screencaptureArgs(path.toString)*).text).map {
          case Result.Success(_) => ToolOutcome.Ok(s"saved screenshot to $path")
          case Result.Failure(e) => ToolOutcome.Error(s"screencapture failed: ${e.getMessage} (macOS only)")
          case Result.Panic(e)   => ToolOutcome.Error(s"screencapture failed: ${String.valueOf(e.getMessage)}")
        }
      case action =>
        cliclickDirective(action, (args / "x").asLong, (args / "y").asLong,
          (args / "text").asStr, (args / "key").asStr) match
          case Left(err) => ToolOutcome.Error(err)
          case Right(directive) =>
            Abort.run[CommandException](Command("cliclick", directive).text).map {
              case Result.Success(_) => ToolOutcome.Ok(s"$action ok")
              case Result.Failure(e) => ToolOutcome.Error(s"cliclick failed: ${e.getMessage} (install cliclick; macOS only)")
              case Result.Panic(e)   => ToolOutcome.Error(s"cliclick failed: ${String.valueOf(e.getMessage)}")
            }
end ComputerUse

package apollo.tools

import apollo.browser.{Cdp, Chrome}
import apollo.config.Fs
import apollo.util.Jx
import apollo.util.Jx.*
import kyo.*
import kyo.Structure.Value

/** browser — drive a real headless Chrome over the DevTools Protocol:
  * navigate, read page content/text, run JS, click a selector, or screenshot.
  * Connects to a running Chrome via `CHROME_CDP_URL`, or launches one when
  * `browser.enabled` and a Chrome/Chromium binary is present. */
object BrowserTool:

  val entries: List[ToolEntry] = List(
    ToolEntry(
      name = "browser",
      toolset = "browser",
      description =
        "Control a headless Chrome via the DevTools Protocol. actions: navigate (url), " +
          "content (rendered HTML), text (visible text), eval (JS expression), click (CSS selector), " +
          "screenshot (saves a PNG). Requires CHROME_CDP_URL or browser.enabled with Chrome installed.",
      parametersJson = """{"type":"object","properties":{
        "action":{"type":"string","enum":["navigate","content","text","eval","click","screenshot"]},
        "url":{"type":"string","description":"URL for navigate"},
        "expression":{"type":"string","description":"JS for eval"},
        "selector":{"type":"string","description":"CSS selector for click"},
        "filename":{"type":"string","description":"screenshot output name (default screenshot-<n>.png)"}
      },"required":["action"]}""".replaceAll("\n\\s*", ""),
      emoji = "🌐",
      available = ctx => Chrome.available(ctx.config),
      handler = handle
    )
  )

  private def handle(args: Value, ctx: ToolContext): ToolOutcome < (Sync & Async) =
    (args / "action").asStr match
      case Absent => ToolOutcome.Error("missing required parameter: action")
      case Present(action) =>
        Chrome.endpoint(ctx.config).map { ep =>
          val out: ToolOutcome < (Sync & Async) = ep match
            case Result.Failure(e) => ToolOutcome.Error(s"browser: $e")
            case Result.Success(base) =>
              Chrome.pageWs(base).map {
                case Result.Failure(e)     => ToolOutcome.Error(s"browser: $e")
                case Result.Success(wsUrl) => runOnWs(wsUrl, action, args, ctx)
              }
          out
        }

  private def runOnWs(wsUrl: String, action: String, args: Value, ctx: ToolContext): ToolOutcome < (Sync & Async) =
    val idGen = new java.util.concurrent.atomic.AtomicLong(0L)
    Abort.run[Throwable](Abort.catching[Throwable](
      Abort.run[HttpException](
        HttpClient.webSocket(wsUrl) { ws =>
          Cdp.call(ws, idGen, "Runtime.enable", Jx.obj()).map(_ => dispatch(action, args, ctx, ws, idGen))
        }
      )
    )).map {
      case Result.Success(Result.Success(o)) => o
      case Result.Success(Result.Failure(e)) => ToolOutcome.Error(s"browser: websocket error: ${e.getMessage}")
      case Result.Success(Result.Panic(e))   => ToolOutcome.Error(s"browser: ${String.valueOf(e.getMessage)}")
      case Result.Failure(e)                 => ToolOutcome.Error(s"browser: ${e.getMessage}")
      case Result.Panic(e)                   => ToolOutcome.Error(s"browser: ${String.valueOf(e.getMessage)}")
    }

  private def dispatch(
      action: String, args: Value, ctx: ToolContext,
      ws: HttpWebSocket, idGen: java.util.concurrent.atomic.AtomicLong
  ): ToolOutcome < (Sync & Async) =
    def outcome(r: Result[String, String]): ToolOutcome =
      r match
        case Result.Success(s) => ToolOutcome.Ok(s)
        case Result.Failure(e) => ToolOutcome.Error(e)
        case Result.Panic(e)   => ToolOutcome.Error(String.valueOf(e.getMessage))
    action match
      case "navigate" => (args / "url").asStr match
        case Present(u) => Cdp.navigate(ws, idGen, u).map(outcome)
        case Absent     => ToolOutcome.Error("navigate requires 'url'")
      case "content" => Cdp.evaluate(ws, idGen, Cdp.jsOuterHtml).map(outcome)
      case "text"    => Cdp.evaluate(ws, idGen, Cdp.jsInnerText).map(outcome)
      case "eval" => (args / "expression").asStr match
        case Present(e) => Cdp.evaluate(ws, idGen, e).map(outcome)
        case Absent     => ToolOutcome.Error("eval requires 'expression'")
      case "click" => (args / "selector").asStr match
        case Present(s) => Cdp.evaluate(ws, idGen, Cdp.clickJs(s)).map(outcome)
        case Absent     => ToolOutcome.Error("click requires 'selector'")
      case "screenshot" =>
        Cdp.screenshot(ws, idGen).map { res =>
          val o: ToolOutcome < (Sync & Async) = res match
            case Result.Success(b64) =>
              val name = (args / "filename").asStr.getOrElse(s"screenshot-${java.util.UUID.randomUUID.toString.take(8)}.png")
              val path = ctx.cwd.resolve(name)
              Fs.writeBytes(path, java.util.Base64.getDecoder.decode(b64)).andThen(ToolOutcome.Ok(s"saved screenshot to $path"))
            case Result.Failure(e) => ToolOutcome.Error(e)
            case Result.Panic(e)   => ToolOutcome.Error(String.valueOf(e.getMessage))
          o
        }
      case other =>
        ToolOutcome.Error(s"unknown browser action '$other' (navigate|content|text|eval|click|screenshot)")
end BrowserTool

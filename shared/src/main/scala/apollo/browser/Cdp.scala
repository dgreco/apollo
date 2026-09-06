package apollo.browser

import apollo.util.Jx
import apollo.util.Jx.*
import kyo.*
import kyo.Structure.Value

/** Chrome DevTools Protocol over a WebSocket: the request/response correlation
  * and the high-level page actions (navigate / evaluate / screenshot / click).
  *
  * The framing and JS builders are pure; the session runners fold over an
  * `HttpWebSocket` (works on both JVM and Native — the same client the Slack /
  * Discord gateways use). Testable end-to-end against a mock CDP WebSocket
  * server; the only live-only piece is a real Chrome (see `Chrome`).
  */
object Cdp:

  final case class Target(id: String, `type`: String, url: String, wsUrl: String)

  // --- pure framing --------------------------------------------------------

  /** A CDP command frame: `{"id":N,"method":"...","params":{...}}`. */
  def command(id: Long, method: String, params: Value): String =
    Jx.render(Jx.obj("id" -> Jx.num(id), "method" -> Jx.str(method), "params" -> params))

  /** Targets from a Chrome `/json` listing (an array of target objects). */
  def parseTargets(json: Value): List[Target] =
    json.asArr.getOrElse(Chunk.empty).toList.flatMap { t =>
      (t / "webSocketDebuggerUrl").asStr.map(ws =>
        Target((t / "id").asStr.getOrElse(""), (t / "type").asStr.getOrElse(""),
          (t / "url").asStr.getOrElse(""), ws)).toList
    }

  /** The debugger WS URL of the first `page` target (else the first target). */
  def firstPageWs(json: Value): Maybe[String] =
    val ts = parseTargets(json)
    Maybe.fromOption(ts.find(_.`type` == "page").orElse(ts.headOption).map(_.wsUrl))

  /** A JS string literal, safely quoted (via JSON encoding). */
  def jsString(s: String): String = Jx.render(Jx.str(s))

  val jsOuterHtml = "document.documentElement.outerHTML"
  val jsInnerText = "document.body ? document.body.innerText : ''"

  /** JS that clicks the first match of `selector` and reports the outcome. */
  def clickJs(selector: String): String =
    val q = jsString(selector)
    s"(function(){var e=document.querySelector($q);if(e){e.click();return 'clicked '+$q;}" +
      s"return 'no element matches '+$q;})()"

  /** Extract the value from a CDP `Runtime.evaluate` result object, rendering
    * non-string values as JSON and surfacing an uncaught exception. */
  def evalValue(cdpResult: Value): Result[String, String] =
    (cdpResult / "exceptionDetails") match
      case Present(ex) =>
        Result.fail((ex / "exception" / "description").asStr
          .orElse((ex / "text").asStr).getOrElse("evaluation threw"))
      case Absent =>
        (cdpResult / "result" / "value") match
          case Present(v) => Result.succeed(v.asStr.getOrElse(Jx.render(v)))
          case Absent     => Result.succeed((cdpResult / "result" / "description").asStr.getOrElse(""))

  // --- session runners (over a connected websocket) ------------------------

  /** Sends one CDP command and returns its matching response `result` (skipping
    * interleaved event frames and other ids), or a readable error. */
  def call(
      ws: HttpWebSocket, idGen: java.util.concurrent.atomic.AtomicLong,
      method: String, params: Value, budget: Int = 400
  ): Result[String, Value] < (Sync & Async) =
    val id = idGen.incrementAndGet()
    Abort.run[Closed](ws.put(HttpWebSocket.Payload.Text(command(id, method, params)))).map {
      case Result.Success(_) => awaitId(ws, id, budget)
      case _                 => Result.fail("cdp: websocket closed on send")
    }

  private def awaitId(ws: HttpWebSocket, id: Long, budget: Int): Result[String, Value] < (Sync & Async) =
    if budget <= 0 then Result.fail(s"cdp: no response for id $id")
    else
      Abort.run[Closed](ws.take()).map {
        case Result.Success(HttpWebSocket.Payload.Text(t)) =>
          Jx.parse(t) match
            case Result.Success(msg) if (msg / "id").asLong == Present(id) =>
              (msg / "error") match
                case Present(err) => Result.fail("cdp: " + (err / "message").asStr.getOrElse("error"))
                case Absent       => Result.succeed((msg / "result").getOrElse(Jx.obj()))
            case _ => awaitId(ws, id, budget - 1) // event or other id
        case Result.Success(_) => awaitId(ws, id, budget - 1) // binary frame
        case _                 => Result.fail(s"cdp: websocket closed before response to id $id")
      }

  /** Reads frames until an event with `method` arrives (or the budget runs
    * out). Used to wait for `Page.loadEventFired` after a navigation. */
  def awaitEvent(ws: HttpWebSocket, method: String, budget: Int = 400): Boolean < (Sync & Async) =
    if budget <= 0 then false
    else
      Abort.run[Closed](ws.take()).map {
        case Result.Success(HttpWebSocket.Payload.Text(t)) =>
          Jx.parse(t) match
            case Result.Success(msg) if (msg / "method").asStr == Present(method) => true
            case _ => awaitEvent(ws, method, budget - 1)
        case Result.Success(_) => awaitEvent(ws, method, budget - 1)
        case _                 => false
      }

  /** `Runtime.evaluate` returning the value as a string. */
  def evaluate(
      ws: HttpWebSocket, idGen: java.util.concurrent.atomic.AtomicLong, expr: String
  ): Result[String, String] < (Sync & Async) =
    call(ws, idGen, "Runtime.evaluate",
      Jx.obj("expression" -> Jx.str(expr), "returnByValue" -> Jx.bool(true))).map {
      case Result.Success(r) => evalValue(r)
      case Result.Failure(e) => Result.fail(e)
      case Result.Panic(e)   => Result.fail(String.valueOf(e.getMessage))
    }

  /** Navigate to `url`, wait for load, and return `title — finalUrl`. */
  def navigate(
      ws: HttpWebSocket, idGen: java.util.concurrent.atomic.AtomicLong, url: String
  ): Result[String, String] < (Sync & Async) =
    call(ws, idGen, "Page.enable", Jx.obj()).map { _ =>
      call(ws, idGen, "Page.navigate", Jx.obj("url" -> Jx.str(url))).map { navRes =>
        val out: Result[String, String] < (Sync & Async) = navRes match
          case Result.Failure(e) => Result.fail(e)
          case Result.Panic(e)   => Result.fail(String.valueOf(e.getMessage))
          case Result.Success(_) =>
            awaitEvent(ws, "Page.loadEventFired").map(_ =>
              evaluate(ws, idGen, "document.title + ' \\u2014 ' + location.href"))
        out
      }
    }

  /** `Page.captureScreenshot` → base64 PNG data (caller saves it). */
  def screenshot(
      ws: HttpWebSocket, idGen: java.util.concurrent.atomic.AtomicLong
  ): Result[String, String] < (Sync & Async) =
    call(ws, idGen, "Page.captureScreenshot", Jx.obj("format" -> Jx.str("png"))).map {
      case Result.Success(r) => (r / "data").asStr match
        case Present(d) => Result.succeed(d)
        case Absent     => Result.fail("cdp: screenshot returned no data")
      case Result.Failure(e) => Result.fail(e)
      case Result.Panic(e)   => Result.fail(String.valueOf(e.getMessage))
    }
end Cdp

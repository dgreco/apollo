// SPDX-FileCopyrightText: 2026 David Greco <greco@acm.org>
//
// SPDX-License-Identifier: Apache-2.0

package apollo.browser

import apollo.util.Jx
import apollo.util.Jx.*
import kyo.*

/** Browser automation: pure CDP framing / discovery + a full DevTools-Protocol
  * round-trip (evaluate / navigate / screenshot) against a mock CDP WebSocket
  * server — no real Chrome needed. */
class BrowserSuite extends munit.FunSuite:

  private def run[A](v: A < (Sync & Async & Scope)): A =
    import AllowUnsafe.embrace.danger
    KyoApp.Unsafe.runAndBlock(60.seconds)(Scope.run(v)).getOrThrow

  // --- pure ----------------------------------------------------------------

  test("command frames a CDP request; parseTargets/firstPageWs read /json") {
    assertEquals(Cdp.command(7L, "Page.navigate", Jx.obj("url" -> Jx.str("https://x"))),
      """{"id":7,"method":"Page.navigate","params":{"url":"https://x"}}""")
    val json = Jx.parse(
      """[{"id":"A","type":"background_page","webSocketDebuggerUrl":"ws://h/bg"},
         {"id":"B","type":"page","url":"https://ex","webSocketDebuggerUrl":"ws://h/page"}]""").getOrElse(Jx.arr())
    assertEquals(Cdp.parseTargets(json).length, 2)
    assertEquals(Cdp.firstPageWs(json), Present("ws://h/page"))
    assertEquals(Cdp.firstPageWs(Jx.arr()), Absent)
  }

  test("clickJs quotes the selector; evalValue extracts / surfaces exceptions") {
    val js = Cdp.clickJs("a.btn[data-x=\"1\"]")
    assert(js.contains("querySelector("), js)
    assert(js.contains("\\\"1\\\""), js) // selector quotes escaped via JSON
    assertEquals(Cdp.evalValue(Jx.obj("result" -> Jx.obj("type" -> Jx.str("string"), "value" -> Jx.str("hi")))),
      Result.succeed("hi"))
    assertEquals(Cdp.evalValue(Jx.obj("result" -> Jx.obj("type" -> Jx.str("number"), "value" -> Jx.num(42L)))),
      Result.succeed("42"))
    assert(Cdp.evalValue(Jx.obj("exceptionDetails" -> Jx.obj("text" -> Jx.str("boom")))).isFailure)
  }

  test("Chrome.launchArgs + findChrome + onPath") {
    val a = Chrome.launchArgs("/bin/chrome", 9333, "/tmp/prof")
    assert(a.contains("--headless=new"), a.toString)
    assert(a.contains("--remote-debugging-port=9333"), a.toString)
    assert(a.contains("--user-data-dir=/tmp/prof"), a.toString)
    assertEquals(Chrome.findChrome(List("/no/such", "/yes/here"), _ == "/yes/here"), Present("/yes/here"))
    assertEquals(Chrome.findChrome(List("/no/such"), _ => false), Absent)
    // onPath: an executable in a real dir
    val dir = java.nio.file.Files.createTempDirectory("apollo-path")
    val exe = dir.resolve("mybrowser")
    java.nio.file.Files.write(exe, Array[Byte](1))
    exe.toFile.setExecutable(true)
    assert(Chrome.onPath("mybrowser", dir.toString))
    assert(!Chrome.onPath("nope", dir.toString))
  }

  // --- E2E over a mock CDP websocket --------------------------------------

  test("evaluate/navigate/screenshot round-trip over a mock DevTools socket") {
    val pngB64 = java.util.Base64.getEncoder.encodeToString("PNGDATA".getBytes("UTF-8"))

    val cdp = HttpHandler.webSocket("/cdp") { (_, ws) =>
      def put(j: String): Unit < (Async & Abort[Closed]) = ws.put(HttpWebSocket.Payload.Text(j)).unit
      def loop: Unit < (Async & Abort[Closed]) =
        ws.take().map {
          case HttpWebSocket.Payload.Text(t) =>
            val msg    = Jx.parse(t).getOrElse(Jx.obj())
            val id     = (msg / "id").asLong.getOrElse(0L)
            val method = (msg / "method").asStr.getOrElse("")
            val respond: Unit < (Async & Abort[Closed]) = method match
              case "Runtime.evaluate" =>
                // an interleaved event the client must skip, then the response
                put("""{"method":"Runtime.consoleAPICalled","params":{}}""")
                  .andThen(put(s"""{"id":$id,"result":{"result":{"type":"string","value":"EVAL_OK"}}}"""))
              case "Page.navigate" =>
                put(s"""{"id":$id,"result":{"frameId":"1"}}""")
                  .andThen(put("""{"method":"Page.loadEventFired","params":{}}"""))
              case "Page.captureScreenshot" =>
                put(s"""{"id":$id,"result":{"data":"$pngB64"}}""")
              case _ => put(s"""{"id":$id,"result":{}}""")
            respond.andThen(loop)
          case _ => loop
        }
      loop
    }

    val (ev, nav, shot) = run {
      Abort.run[HttpBindException](HttpServer.init(0, "127.0.0.1")(cdp)).map {
        case Result.Success(server) =>
          val wsUrl = s"ws://127.0.0.1:${server.port}/cdp"
          Abort.run[HttpException](HttpClient.webSocket(wsUrl) { ws =>
            val idGen = new java.util.concurrent.atomic.AtomicLong(0L)
            for
              ev   <- Cdp.evaluate(ws, idGen, "1+1")
              nav  <- Cdp.navigate(ws, idGen, "https://example.com")
              shot <- Cdp.screenshot(ws, idGen)
            yield (ev, nav, shot)
          }).map {
            case Result.Success(triple) => triple
            case other                  => throw new AssertionError(s"ws failed: $other")
          }
        case other => throw new AssertionError(s"bind failed: $other")
      }
    }

    assertEquals(ev, Result.succeed("EVAL_OK"))
    assertEquals(nav, Result.succeed("EVAL_OK")) // navigate's post-load evaluate
    assertEquals(shot, Result.succeed(pngB64))
  }
end BrowserSuite

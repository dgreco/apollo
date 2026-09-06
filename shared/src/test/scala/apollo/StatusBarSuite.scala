package apollo

import apollo.cli.StatusBar

class StatusBarSuite extends munit.FunSuite:

  test("human formats k/M without a Formatter") {
    assertEquals(StatusBar.human(500), "500")
    assertEquals(StatusBar.human(1234), "1.2k")
    assertEquals(StatusBar.human(2000000), "2.0M")
  }

  test("secs formats milliseconds") {
    assertEquals(StatusBar.secs(3200), "3.2s")
    assertEquals(StatusBar.secs(0), "0.0s")
  }

  test("gauge fills proportionally and clamps") {
    assertEquals(StatusBar.gauge(0.0, 8), "░░░░░░░░")
    assertEquals(StatusBar.gauge(1.0, 8), "▓▓▓▓▓▓▓▓")
    assertEquals(StatusBar.gauge(0.5, 8), "▓▓▓▓░░░░")
    assertEquals(StatusBar.gauge(2.0, 4), "▓▓▓▓")   // clamped to 1.0
    assertEquals(StatusBar.gauge(-1.0, 4), "░░░░")  // clamped to 0.0
  }

  test("render shows model, context %, tokens, and time") {
    val s = StatusBar.render("gpt-5", ctxTokens = 20000, ctxWindow = 200000,
      inTok = 1200, outTok = 340, turnMs = 3200)
    assert(s.contains("gpt-5"), s)
    assert(s.contains("ctx 10%"), s)
    assert(s.contains("20.0k/200.0k"), s)
    assert(s.contains("1.2k in / 340 out"), s)
    assert(s.contains("3.2s"), s)
  }

  test("render without a context window omits the percentage") {
    val s = StatusBar.render("m", ctxTokens = 50, ctxWindow = 0, inTok = 1, outTok = 2, turnMs = 100)
    assert(s.contains("ctx 50"), s)
    assert(!s.contains("%"), s)
  }
end StatusBarSuite

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

  test("token rate: out tokens per second, 0 before any turn") {
    assertEquals(StatusBar.rate(340, 3200), 106L)
    assertEquals(StatusBar.rate(100, 0), 0L)
    val s = StatusBar.render("gpt-5", 20000, 200000, 1200, 340, 3200)
    assert(s.contains("106 t/s"), s)
    // no rate segment when idle (no turn yet)
    assert(!StatusBar.render("gpt-5", 0, 200000, 0, 0, 0).contains("t/s"))
    println("\nstatus bar sample: " + StatusBar.render("deepseek-v4-flash", 16400, 1000000, 4200, 980, 9200))
  }

  test("bar: segments, right-aligned title, full width, and truncation") {
    val b = StatusBar.bar(100, "deepseek-v4-flash", 16400, 1000000, 4200, 980, 9200, "Friendly greeting", colored = false)
    assert(b.contains("‡ deepseek-v4-flash"), b)
    assert(b.contains("16.4k/1.0M"), b)
    assert(b.contains("1%"), b)
    assert(b.contains("⊙ 9.2s"), b)
    assert(b.contains("↑ 106 t/s"), b)
    assert(b.contains("— Friendly greeting"), b)
    assertEquals(b.length, 100, s"bar not padded to width: len=${b.length}")
    assert(b.endsWith("— Friendly greeting"), "title right-aligned")
    // no width → left segments only, no padding
    val nw = StatusBar.bar(0, "m", 100, 200000, 1, 2, 100, "t", colored = false)
    assert(!nw.contains("     "), nw)         // no big padding run
    assert(nw.contains("‡ m"), nw)
    // narrow width → title truncated with an ellipsis, still fits
    val nar = StatusBar.bar(50, "model-x", 100, 200000, 1, 2, 100, "a very long session title here", colored = false)
    assert(nar.length <= 50, s"overflow: len=${nar.length} [$nar]")
  }
end StatusBarSuite

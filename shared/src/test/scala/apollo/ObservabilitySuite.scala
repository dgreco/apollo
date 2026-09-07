package apollo.obs

import apollo.util.Jx
import apollo.util.Jx.*
import kyo.*

class ObservabilitySuite extends munit.FunSuite:

  private def run[A](v: A < (Sync & Async & Scope)): A =
    import AllowUnsafe.embrace.danger
    KyoApp.Unsafe.runAndBlock(30.seconds)(Scope.run(v)).getOrThrow

  // --- OTLP metrics body --------------------------------------------------

  test("metricsJson builds resourceMetrics with a monotonic sum and a histogram") {
    val body = Otlp.metricsJson("apollo", 1_000_000L, 2_000_000L,
      counters = List(Otlp.CounterView("apollo.turns", 5L)),
      histos   = List(Otlp.HistoView("apollo.turn_ms", 3L, 300.0, 10.0, 200.0)))
    val js = Jx.parse(body).getOrElse(Jx.obj())
    val rm = (js / "resourceMetrics").asArr.getOrElse(Chunk.empty).head
    assertEquals((((rm / "resource" / "attributes").asArr.getOrElse(Chunk.empty).head) / "value" / "stringValue").asStr,
      Present("apollo"))
    val metrics = ((rm / "scopeMetrics").asArr.getOrElse(Chunk.empty).head / "metrics").asArr.getOrElse(Chunk.empty)
    val sum = metrics.find(m => (m / "name").asStr == Present("apollo.turns")).get
    assertEquals(((sum / "sum" / "dataPoints").asArr.getOrElse(Chunk.empty).head / "asInt").asStr, Present("5"))
    assertEquals((sum / "sum" / "isMonotonic").asBool, Present(true))
    val h = metrics.find(m => (m / "name").asStr == Present("apollo.turn_ms")).get
    val hp = (h / "histogram" / "dataPoints").asArr.getOrElse(Chunk.empty).head
    assertEquals((hp / "count").asStr, Present("3"))
    assertEquals((hp / "sum").asLong, Present(300L))
  }

  // --- OTLP logs body -----------------------------------------------------

  test("logsJson builds resourceLogs with severity, body, and trace context") {
    val body = Otlp.logsJson("apollo", List(
      Otlp.LogRecord(1_500_000L, "INFO", 9, "turn end", List(Otlp.Attr.S("k", "v")),
        traceId = Some("trace1"), spanId = Some("span1"))))
    val js = Jx.parse(body).getOrElse(Jx.obj())
    val rec = (((js / "resourceLogs").asArr.getOrElse(Chunk.empty).head / "scopeLogs").asArr.getOrElse(Chunk.empty).head
      / "logRecords").asArr.getOrElse(Chunk.empty).head
    assertEquals((rec / "severityText").asStr, Present("INFO"))
    assertEquals((rec / "severityNumber").asLong, Present(9L))
    assertEquals((rec / "body" / "stringValue").asStr, Present("turn end"))
    assertEquals((rec / "traceId").asStr, Present("trace1"))
    assertEquals((rec / "spanId").asStr, Present("span1"))
  }

  // --- trace span tree ----------------------------------------------------

  test("TraceContext nests llm.call and tool.* spans under agent.turn") {
    val tc   = new TraceContext("apollo")
    val root = tc.begin("agent.turn")
    val llm  = tc.begin("llm.call")
    tc.end(llm, error = false, List(Otlp.Attr.S("provider", "openai")))
    val tool = tc.begin("tool.read_file")
    tc.end(tool, error = false)
    tc.end(root, error = false, List(Otlp.Attr.S("exit_reason", "text_response")))

    val spans = tc.spans
    assertEquals(spans.length, 3)
    val rootSpan = spans.find(_.name == "agent.turn").get
    val llmSpan  = spans.find(_.name == "llm.call").get
    val toolSpan = spans.find(_.name == "tool.read_file").get
    assertEquals(rootSpan.parent, None)                 // root has no parent
    assertEquals(llmSpan.parent, Some(rootSpan.spanId)) // children nest under root
    assertEquals(toolSpan.parent, Some(rootSpan.spanId))
    assertEquals(tc.currentSpanId, None)                // balanced begins/ends
    val tree = tc.render
    assert(tree.contains("agent.turn") && tree.contains("llm.call") && tree.contains("tool.read_file"), tree)
    assert(tree.contains("provider=openai"), tree)
  }

  // --- log level parsing --------------------------------------------------

  test("ObsLog.parseLevel maps names, defaulting unknown to silent") {
    assertEquals(ObsLog.parseLevel("trace"), Log.Level.trace)
    assertEquals(ObsLog.parseLevel("WARN"), Log.Level.warn)
    assertEquals(ObsLog.parseLevel("error"), Log.Level.error)
    assertEquals(ObsLog.parseLevel("silent"), Log.Level.silent)
    assertEquals(ObsLog.parseLevel("nonsense"), Log.Level.silent)
  }

  test("ObsLog buffers OTLP log records only while capture is on, stamped with trace context") {
    val tc = new TraceContext("apollo")
    tc.begin("agent.turn")
    ObsLog.drain() // clear any residue
    // capture off: nothing buffered
    ObsLog.setCapture(false)
    run(ObsLog.info("ignored", Present(tc)))
    assert(ObsLog.drain().isEmpty)
    // capture on: one stamped record
    ObsLog.setCapture(true)
    run(ObsLog.debug("turn start", Present(tc)))
    val recs = ObsLog.drain()
    ObsLog.setCapture(false)
    assertEquals(recs.length, 1)
    assertEquals(recs.head.severityText, "DEBUG")
    assertEquals(recs.head.body, "turn start")
    assertEquals(recs.head.traceId, Some(tc.traceId))
  }

  // --- in-process metrics -------------------------------------------------

  test("Metrics counters advance and render exposes latency histograms") {
    def turns  = Metrics.snapshot._1.find(_.name == "apollo.turns").get.value
    def tools  = Metrics.snapshot._1.find(_.name == "apollo.tool_calls").get.value
    val t0 = turns
    val k0 = tools
    run(Metrics.turnCompleted("text_response", apiCalls = 2L, inTok = 10L, outTok = 20L, turnMs = 1200L))
    run(Metrics.toolCall(15L, error = false))
    run(Metrics.llmCall(900L))
    assertEquals(turns, t0 + 1)
    assertEquals(tools, k0 + 1)
    val r = Metrics.render
    assert(r.contains("counters:") && r.contains("turn_ms") && r.contains("llm_ms"), r)
  }
end ObservabilitySuite

// SPDX-FileCopyrightText: 2026 David Greco <greco@acm.org>
//
// SPDX-License-Identifier: Apache-2.0

package apollo.obs

import apollo.config.{ApolloConfig, ApolloPaths, EnvChain, Yaml}
import apollo.util.Jx
import apollo.util.Jx.*
import kyo.*

class MonitorSuite extends munit.FunSuite:

  private def run[A](v: A < (Sync & Async & Scope)): A =
    import AllowUnsafe.embrace.danger
    KyoApp.Unsafe.runAndBlock(30.seconds)(Scope.run(v)).getOrThrow

  test("traceJson builds OTLP resourceSpans with content-free attributes") {
    val span = Otlp.Span("agent.turn", "abcd", None, 1_000_000L, 2_000_000L,
      List(Otlp.Attr.S("exit_reason", "text_response"), Otlp.Attr.I("api_calls", 3L), Otlp.Attr.B("error", false)),
      error = false)
    val js = Jx.parse(Otlp.traceJson("apollo", "trace1", List(span))).getOrElse(Jx.obj())
    val rs = (js / "resourceSpans").asArr.getOrElse(Chunk.empty).head
    assertEquals(
      ((rs / "resource" / "attributes").asArr.getOrElse(Chunk.empty).head / "value" / "stringValue").asStr,
      Present("apollo"))
    val s0 = ((rs / "scopeSpans").asArr.getOrElse(Chunk.empty).head / "spans").asArr.getOrElse(Chunk.empty).head
    assertEquals((s0 / "name").asStr, Present("agent.turn"))
    assertEquals((s0 / "traceId").asStr, Present("trace1"))
    assertEquals((s0 / "startTimeUnixNano").asStr, Present("1000000"))
    assertEquals((s0 / "status" / "code").asLong, Present(1L))
  }

  test("exportTurn posts a content-free trace to the OTLP endpoint") {
    val captured = new java.util.concurrent.ConcurrentLinkedQueue[String]()
    val route = HttpRoute.postText("v1" / "traces").handler { r =>
      captured.add(r.fields.body)
      HttpResponse.ok("{}").setHeader("content-type", "application/json")
    }
    run {
      Abort.run[HttpBindException](HttpServer.init(0, "127.0.0.1")(route)).map {
        case Result.Success(server) =>
          val paths  = ApolloPaths(java.nio.file.Files.createTempDirectory("apollo-otlp"))
          val config = ApolloConfig(
            Present(Yaml.parse("monitoring: {export: {otlp: {enabled: true}}}").getOrElse(throw new AssertionError("yaml"))),
            EnvChain(Map("OTEL_EXPORTER_OTLP_ENDPOINT" -> s"http://127.0.0.1:${server.port}")), paths)
          assert(Monitor.enabled(config))
          Monitor.exportTurn(config, "text_response", apiCalls = 2L, inTokens = 10L, outTokens = 20L,
            startMs = 1000L, endMs = 1500L,
            tools = List(Monitor.ToolTiming("read_file", 1050L, 1100L, error = false)))
        case other => throw new AssertionError(s"bind failed: $other")
      }
    }
    import scala.jdk.CollectionConverters.*
    val body = captured.asScala.mkString
    assert(body.nonEmpty, "no OTLP trace posted")
    assert(body.contains("agent.turn") && body.contains("tool.read_file"), body)
    assert(body.contains("text_response") && body.contains("tokens.input"), body)
    // content-free: no prompt/args/results leaked
    assert(!body.contains("read_file") || !body.contains("arguments"), body)
  }
end MonitorSuite

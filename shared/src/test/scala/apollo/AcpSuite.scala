// SPDX-FileCopyrightText: 2026 David Greco <greco@acm.org>
//
// SPDX-License-Identifier: Apache-2.0

package apollo.acp

import apollo.config.{ApolloConfig, ApolloPaths, EnvChain}
import apollo.gateway.SessionHub
import apollo.provider.{ApiMode, ResolvedRuntime}
import apollo.util.Jx
import apollo.util.Jx.*
import kyo.*

class AcpSuite extends munit.FunSuite:

  private def run[A](v: A < (Sync & Async & Scope)): A =
    import AllowUnsafe.embrace.danger
    KyoApp.Unsafe.runAndBlock(60.seconds)(Scope.run(v)).getOrThrow

  private def p(json: String) = Jx.parse(json).getOrElse(Jx.obj())

  test("parse + framing + ACP shapes") {
    val req = Acp.parse("""{"jsonrpc":"2.0","id":5,"method":"initialize","params":{"protocolVersion":1}}""")
    assertEquals(req.map(_.method), Some("initialize"))
    assertEquals(req.flatMap(_.id), Some(5L))
    assertEquals(Acp.parse("not json"), None)
    assert(Acp.result(1L, Jx.obj("ok" -> Jx.bool(true))).contains("\"id\":1"))
    assert(Acp.error(1L, -32601, "nope").contains("-32601"))
    assert(Acp.notification("session/update", Jx.obj()).contains("\"method\":\"session/update\""))
    assertEquals((Acp.initializeResult / "protocolVersion").asLong, Present(1L))
    assertEquals(Acp.promptText(p("""{"prompt":[{"type":"text","text":"hi "},{"type":"text","text":"there"}]}""")), "hi there")
    assertEquals(Acp.sessionId(p("""{"sessionId":"s1"}""")), Present("s1"))
    val mc = p(Acp.messageChunk("s1", "hello")).field("params").getOrElse(Jx.obj())
    assertEquals((mc / "update" / "sessionUpdate").asStr, Present("agent_message_chunk"))
    assertEquals((mc / "update" / "content" / "text").asStr, Present("hello"))
  }

  test("E2E: initialize → session/new → session/prompt streams a reply + end_turn") {
    val sent = new java.util.concurrent.ConcurrentLinkedQueue[String]()
    val provider = HttpRoute.postText("chat" / "completions").handler { _ =>
      HttpResponse.ok(Jx.render(Jx.obj(
        "choices" -> Jx.arr(Jx.obj("message" -> Jx.obj("role" -> Jx.str("assistant"), "content" -> Jx.str("ok from agent")),
          "finish_reason" -> Jx.str("stop"))),
        "usage" -> Jx.obj("prompt_tokens" -> Jx.num(1L), "completion_tokens" -> Jx.num(1L))
      ))).setHeader("content-type", "application/json")
    }
    run {
      Abort.run[HttpBindException](HttpServer.init(0, "127.0.0.1")(provider)).map {
        case Result.Success(server) =>
          val baseUrl = s"http://127.0.0.1:${server.port}"
          val home    = java.nio.file.Files.createTempDirectory("apollo-acp")
          java.nio.file.Files.createDirectories(home.resolve("scala-state").resolve("sessions"))
          val config  = ApolloConfig(Absent, EnvChain(Map.empty), ApolloPaths(home))
          val runtime = ResolvedRuntime(
            providerSlug = "custom", displayName = "mock", model = "m", baseUrl = baseUrl,
            apiKey = Present("k"), apiMode = ApiMode.ChatCompletions, headers = Map.empty, profile = Absent,
            reasoning = Absent, maxTokens = Absent, contextLength = Absent, streaming = false)
          val hub  = new SessionHub(config, ApolloPaths(home), runtime)
          val send = (line: String) => Sync.defer { sent.add(line); () }
          for
            _ <- AcpServer.handleLine("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}""", hub, send)
            _ <- AcpServer.handleLine("""{"jsonrpc":"2.0","id":2,"method":"session/new","params":{"cwd":"/tmp"}}""", hub, send)
            sid = extractSessionId(sent)
            _ <- AcpServer.handleLine(
                   s"""{"jsonrpc":"2.0","id":3,"method":"session/prompt","params":{"sessionId":"$sid","prompt":[{"type":"text","text":"hello"}]}}""",
                   hub, send)
          yield ()
        case other => throw new AssertionError(s"bind failed: $other")
      }
    }
    import scala.jdk.CollectionConverters.*
    val out = sent.asScala.toList
    // initialize result
    assert(out.exists(l => l.contains("\"id\":1") && l.contains("protocolVersion")), out.toString)
    // session/new result carried a sessionId
    assert(out.exists(l => l.contains("\"id\":2") && l.contains("sessionId")), out.toString)
    // streamed the assistant text as an agent_message_chunk
    assert(out.exists(l => l.contains("agent_message_chunk") && l.contains("ok from agent")), out.toString)
    // prompt result with stopReason end_turn
    assert(out.exists(l => l.contains("\"id\":3") && l.contains("end_turn")), out.toString)
  }

  private def extractSessionId(sent: java.util.concurrent.ConcurrentLinkedQueue[String]): String =
    import scala.jdk.CollectionConverters.*
    sent.asScala.toList.flatMap(l => (Jx.parse(l).getOrElse(Jx.obj()) / "result" / "sessionId").asStr.toList).headOption
      .getOrElse(throw new AssertionError("no sessionId issued"))
end AcpSuite

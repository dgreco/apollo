package apollo.gateway

import apollo.config.{ApolloConfig, ApolloPaths, EnvChain}
import apollo.provider.{ApiMode, ResolvedRuntime}
import apollo.util.Jx
import kyo.*

/** WhatsApp Cloud connector: pure parse/verify/body units + an E2E where a
  * message drives a turn and the reply is POSTed to a mock Graph API. */
class WhatsAppSuite extends munit.FunSuite:

  private def run[A](v: A < (Sync & Async & Scope)): A =
    import AllowUnsafe.embrace.danger
    KyoApp.Unsafe.runAndBlock(60.seconds)(Scope.run(v)).getOrThrow

  test("verifyChallenge echoes only for a valid subscribe") {
    assertEquals(WhatsApp.verifyChallenge("subscribe", "tok", "1234", "tok"), Present("1234"))
    assertEquals(WhatsApp.verifyChallenge("subscribe", "wrong", "1234", "tok"), Absent)
    assertEquals(WhatsApp.verifyChallenge("subscribe", "", "1234", ""), Absent) // no configured token
  }

  test("parseMessages extracts text messages + phone id, ignores non-text") {
    val json = Jx.parse(
      """{"entry":[{"changes":[{"value":{
        "metadata":{"phone_number_id":"PID"},
        "messages":[
          {"from":"15551230000","type":"text","text":{"body":"hi"}},
          {"from":"15551230000","type":"image","image":{"id":"x"}}
        ]}}]}]}""").getOrElse(Jx.obj())
    assertEquals(WhatsApp.parseMessages(json), List(WhatsApp.Msg("15551230000", "hi", "PID")))
    assertEquals(WhatsApp.parseMessages(Jx.obj()), Nil)
  }

  test("sendBody shapes a WhatsApp text message") {
    val b = WhatsApp.sendBody("15551230000", "hello there")
    assert(b.contains("\"messaging_product\""), b)
    assert(b.contains("\"to\":\"15551230000\""), b)
    assert(b.contains("hello there"), b)
  }

  test("E2E: a message drives a turn and the reply is POSTed to the Graph API") {
    val sends = new java.util.concurrent.ConcurrentLinkedQueue[String]()

    val graph = HttpRoute.postText(
      kyo.Capture[String]("ver") / kyo.Capture[String]("pid") / "messages"
    ).handler { r =>
      sends.add(r.fields.body)
      HttpResponse.ok(Jx.render(Jx.obj("messages" -> Jx.arr(Jx.obj("id" -> Jx.str("wamid.1"))))))
        .setHeader("content-type", "application/json")
    }
    val provider = HttpRoute.postText("chat" / "completions").handler { _ =>
      HttpResponse.ok(Jx.render(Jx.obj(
        "choices" -> Jx.arr(Jx.obj("message" -> Jx.obj("role" -> Jx.str("assistant"), "content" -> Jx.str("ok")),
          "finish_reason" -> Jx.str("stop"))),
        "usage" -> Jx.obj("prompt_tokens" -> Jx.num(1L), "completion_tokens" -> Jx.num(1L))
      ))).setHeader("content-type", "application/json")
    }

    run {
      Abort.run[HttpBindException](HttpServer.init(0, "127.0.0.1")(graph, provider)).map {
        case Result.Success(server) =>
          val baseUrl = s"http://127.0.0.1:${server.port}"
          val home    = java.nio.file.Files.createTempDirectory("apollo-whatsapp")
          java.nio.file.Files.createDirectories(home.resolve("scala-state").resolve("sessions"))
          val config = ApolloConfig(Absent, EnvChain(Map(
            "WHATSAPP_API_BASE"       -> baseUrl,
            "WHATSAPP_ALLOW_ALL_USERS" -> "1"
          )), ApolloPaths(home))
          val runtime = ResolvedRuntime(
            providerSlug = "custom", displayName = "mock", model = "m", baseUrl = baseUrl,
            apiKey = Present("k"), apiMode = ApiMode.ChatCompletions, headers = Map.empty, profile = Absent,
            reasoning = Absent, maxTokens = Absent, contextLength = Absent, streaming = false)
          val hub = new SessionHub(config, ApolloPaths(home), runtime)
          WhatsApp.handle(WhatsApp.Msg("15551230000", "hello whatsapp", "PID123"),
            token = "tok", apiVersion = "v21.0", config = config, hub = hub).map { _ =>
            import scala.jdk.CollectionConverters.*
            def settled = sends.asScala.exists(_.contains("ok"))
            def await(ms: Long): Unit < (Sync & Async) =
              if settled || ms <= 0 then () else Async.sleep(150.millis).andThen(await(ms - 150))
            await(30000)
          }
        case other => throw new AssertionError(s"bind failed: $other")
      }
    }

    import scala.jdk.CollectionConverters.*
    val bodies = sends.asScala.toList
    assert(bodies.exists(b => b.contains("\"messaging_product\"") && b.contains("ok") &&
      b.contains("15551230000")), bodies.toString)
  }
end WhatsAppSuite

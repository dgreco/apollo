package apollo.gateway

import apollo.config.{ApolloConfig, ApolloPaths, EnvChain}
import apollo.provider.{ApiMode, ResolvedRuntime}
import apollo.util.Jx
import apollo.util.Jx.*
import kyo.*

/** Twilio SMS connector: pure form/body/auth units + an E2E where an inbound
  * text drives a turn and the reply is POSTed to a mock Twilio REST API. */
class SmsSuite extends munit.FunSuite:

  private def run[A](v: A < (Sync & Async & Scope)): A =
    import AllowUnsafe.embrace.danger
    KyoApp.Unsafe.runAndBlock(60.seconds)(Scope.run(v)).getOrThrow

  test("parseForm decodes url-encoded Twilio fields") {
    val f = Sms.parseForm("From=%2B15551112222&To=%2B15550000000&Body=hello+there&MessageSid=SM1")
    assertEquals(f.get("From"), Some("+15551112222"))
    assertEquals(f.get("To"), Some("+15550000000"))
    assertEquals(f.get("Body"), Some("hello there"))
    assertEquals(Sms.parseForm(""), Map.empty)
  }

  test("sendBody form-encodes and basicAuth base64s sid:token") {
    val b = Sms.sendBody("+15550000000", "+15551112222", "hi & bye")
    assert(b.contains("From=%2B15550000000"), b)
    assert(b.contains("To=%2B15551112222"), b)
    assert(b.contains("Body=hi+%26+bye") || b.contains("Body=hi%20%26%20bye"), b)
    // base64("AC:tok") == "QUM6dG9r"
    assertEquals(Sms.basicAuth("AC", "tok"), "Basic QUM6dG9r")
  }

  test("E2E: an inbound text drives a turn and the reply is POSTed to Twilio") {
    val sends = new java.util.concurrent.ConcurrentLinkedQueue[String]()
    val auths = new java.util.concurrent.ConcurrentLinkedQueue[String]()

    val twilio = HttpRoute.postText(
      "2010-04-01" / "Accounts" / kyo.Capture[String]("sid") / "Messages.json"
    ).handler { r =>
      sends.add(r.fields.body)
      r.headers.get("authorization") match { case Present(a) => auths.add(a); case Absent => () }
      HttpResponse.ok(Jx.render(Jx.obj("sid" -> Jx.str("SM123"), "status" -> Jx.str("queued"))))
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
      Abort.run[HttpBindException](HttpServer.init(0, "127.0.0.1")(twilio, provider)).map {
        case Result.Success(server) =>
          val baseUrl = s"http://127.0.0.1:${server.port}"
          val home    = java.nio.file.Files.createTempDirectory("apollo-sms")
          java.nio.file.Files.createDirectories(home.resolve("scala-state").resolve("sessions"))
          val config = ApolloConfig(Absent, EnvChain(Map(
            "TWILIO_API_BASE"     -> baseUrl,
            "TWILIO_ACCOUNT_SID"  -> "AC123",
            "TWILIO_AUTH_TOKEN"   -> "authtok",
            "TWILIO_FROM_NUMBER"  -> "+15550000000",
            "SMS_ALLOW_ALL_USERS" -> "1"
          )), ApolloPaths(home))
          val runtime = ResolvedRuntime(
            providerSlug = "custom", displayName = "mock", model = "m", baseUrl = baseUrl,
            apiKey = Present("k"), apiMode = ApiMode.ChatCompletions, headers = Map.empty, profile = Absent,
            reasoning = Absent, maxTokens = Absent, contextLength = Absent, streaming = false)
          val hub = new SessionHub(config, ApolloPaths(home), runtime)
          Sms.handle(Sms.Msg("+15551112222", "+15550000000", "hello sms"), config, hub).map { _ =>
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
    assert(bodies.nonEmpty, "no send recorded")
    val form = Sms.parseForm(bodies.head)
    assertEquals(form.get("To"), Some("+15551112222"))
    assertEquals(form.get("From"), Some("+15550000000"))
    assert(form.get("Body").exists(_.contains("ok")), form.toString)
    assert(auths.asScala.exists(_ == Sms.basicAuth("AC123", "authtok")), auths.toString)
  }
end SmsSuite

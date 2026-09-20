package apollo.gateway

import apollo.config.{ApolloConfig, ApolloPaths, EnvChain}
import apollo.provider.{ApiMode, ResolvedRuntime}
import apollo.util.Jx
import kyo.*

/** Teams (Bot Framework) connector: pure parse/token units + an E2E where an
  * activity drives a turn and the reply is posted back with a minted bearer. */
class TeamsSuite extends munit.FunSuite:

  private def run[A](v: A < (Sync & Async & Scope)): A =
    import AllowUnsafe.embrace.danger
    KyoApp.Unsafe.runAndBlock(60.seconds)(Scope.run(v)).getOrThrow

  private def p(json: String) = Jx.parse(json).getOrElse(Jx.obj())

  test("parseActivity reads message activities, ignores others") {
    val a = Teams.parseActivity(p(
      """{"type":"message","text":"hi bot","id":"act1","serviceUrl":"https://smba/",
         "from":{"id":"u1","name":"Ada"},"conversation":{"id":"conv1"}}"""))
    assertEquals(a.map(_.text), Some("hi bot"))
    assertEquals(a.map(_.conversationId), Some("conv1"))
    assertEquals(a.map(_.fromId), Some("u1"))
    assertEquals(a.map(_.activityId), Some("act1"))
    assertEquals(Teams.parseActivity(p("""{"type":"typing"}""")), None)
    assertEquals(Teams.parseActivity(p("""{"type":"message","text":"x"}""")), None) // no conversation/serviceUrl
  }

  test("replyBody / tokenForm / parseToken") {
    assert(Teams.replyBody("hello").contains("\"type\":\"message\""))
    val form = Teams.tokenForm("appid", "sec ret")
    assert(form.contains("grant_type=client_credentials"), form)
    assert(form.contains("client_id=appid"), form)
    assert(form.contains("client_secret=sec+ret") || form.contains("client_secret=sec%20ret"), form)
    assert(form.contains("scope=https%3A%2F%2Fapi.botframework.com%2F.default"), form)
    Teams.parseToken(p("""{"access_token":"AAD","expires_in":3600}"""), 1000L) match
      case Result.Success((t, exp)) => assertEquals(t, "AAD"); assertEquals(exp, 1000L + 3_600_000L)
      case other                    => fail(s"expected success, got $other")
    assert(Teams.parseToken(p("""{"error_description":"bad"}"""), 0L).isFailure)
  }

  test("E2E: an activity drives a turn and the reply posts back with a bearer") {
    Teams.resetState()
    val replies = new java.util.concurrent.ConcurrentLinkedQueue[String]()
    val auths   = new java.util.concurrent.ConcurrentLinkedQueue[String]()

    val tokenR = HttpRoute.postText("botframework.com" / "oauth2" / "v2.0" / "token").handler { _ =>
      HttpResponse.ok(Jx.render(Jx.obj("access_token" -> Jx.str("AAD-TOKEN"), "expires_in" -> Jx.num(3600L))))
        .setHeader("content-type", "application/json")
    }
    val reply = HttpRoute.postText(
      "v3" / "conversations" / kyo.Capture[String]("conv") / "activities" / kyo.Capture[String]("act")
    ).handler { r =>
      replies.add(r.fields.body)
      r.headers.get("authorization") match { case Present(a) => auths.add(a); case Absent => () }
      HttpResponse.ok(Jx.render(Jx.obj("id" -> Jx.str("sent1")))).setHeader("content-type", "application/json")
    }
    val provider = HttpRoute.postText("chat" / "completions").handler { _ =>
      HttpResponse.ok(Jx.render(Jx.obj(
        "choices" -> Jx.arr(Jx.obj("message" -> Jx.obj("role" -> Jx.str("assistant"), "content" -> Jx.str("ok")),
          "finish_reason" -> Jx.str("stop"))),
        "usage" -> Jx.obj("prompt_tokens" -> Jx.num(1L), "completion_tokens" -> Jx.num(1L))
      ))).setHeader("content-type", "application/json")
    }

    run {
      Abort.run[HttpBindException](HttpServer.init(0, "127.0.0.1")(tokenR, reply, provider)).map {
        case Result.Success(server) =>
          val baseUrl = s"http://127.0.0.1:${server.port}"
          val home    = java.nio.file.Files.createTempDirectory("apollo-teams")
          java.nio.file.Files.createDirectories(home.resolve("scala-state").resolve("sessions"))
          val config = ApolloConfig(Absent, EnvChain(Map(
            "TEAMS_APP_ID"        -> "app",
            "TEAMS_APP_PASSWORD"  -> "pw",
            "TEAMS_LOGIN_BASE"    -> baseUrl,
            "TEAMS_ALLOW_ALL_USERS" -> "1"
          )), ApolloPaths(home))
          val runtime = ResolvedRuntime(
            providerSlug = "custom", displayName = "mock", model = "m", baseUrl = baseUrl,
            apiKey = Present("k"), apiMode = ApiMode.ChatCompletions, headers = Map.empty, profile = Absent,
            reasoning = Absent, maxTokens = Absent, contextLength = Absent, streaming = false)
          val hub = new SessionHub(config, ApolloPaths(home), runtime)
          val activity = Teams.Activity(
            text = "hello teams", fromId = "u1", fromName = "Ada",
            conversationId = "conv1", activityId = "act1", serviceUrl = baseUrl)
          Teams.handle(activity, config, hub).map { _ =>
            import scala.jdk.CollectionConverters.*
            def settled = replies.asScala.exists(_.contains("ok"))
            def await(ms: Long): Unit < (Sync & Async) =
              if settled || ms <= 0 then () else Async.sleep(150.millis).andThen(await(ms - 150))
            await(30000)
          }
        case other => throw new AssertionError(s"bind failed: $other")
      }
    }

    import scala.jdk.CollectionConverters.*
    val bodies = replies.asScala.toList
    assert(bodies.exists(b => b.contains("\"type\":\"message\"") && b.contains("ok")), bodies.toString)
    assert(auths.asScala.exists(_ == "Bearer AAD-TOKEN"), auths.toString)
  }
end TeamsSuite

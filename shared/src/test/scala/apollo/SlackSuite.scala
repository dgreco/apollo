package apollo.gateway

import apollo.config.{ApolloConfig, ApolloPaths, EnvChain}
import apollo.provider.{ApiMode, ResolvedRuntime}
import apollo.util.Jx
import apollo.util.Jx.*
import kyo.*
import kyo.Structure.Value

/** Slack connector: allowlist/mrkdwn units + a Socket Mode E2E (fake wss +
  * fake Web API + fake provider) through a real SessionHub.
  */
class SlackSuite extends munit.FunSuite:

  private def run[A](v: A < (Sync & Async & Scope)): A =
    import AllowUnsafe.embrace.danger
    KyoApp.Unsafe.runAndBlock(60.seconds)(Scope.run(v)).getOrThrow

  // --- unit ----------------------------------------------------------------

  test("authorizedUser: deny-by-default, allow-all, membership, wildcard") {
    def g(users: Set[String], all: Boolean) = Slack.Gates(users, all, "none", true)
    assert(!Slack.authorizedUser(g(Set.empty, false), "U1"))     // nothing set → deny
    assert(Slack.authorizedUser(g(Set.empty, true), "U1"))       // allow-all
    assert(Slack.authorizedUser(g(Set("U1"), false), "U1"))      // listed
    assert(!Slack.authorizedUser(g(Set("U1"), false), "U2"))     // not listed
    assert(Slack.authorizedUser(g(Set("*"), false), "Uany"))     // wildcard
  }

  test("toMrkdwn converts bold/links and defuses broadcast pings") {
    assertEquals(Slack.toMrkdwn("**bold**"), "*bold*")
    assertEquals(Slack.toMrkdwn("[docs](https://x.dev)"), "<https://x.dev|docs>")
    assert(Slack.toMrkdwn("<!channel> hi").startsWith("<!channel|channel>"))
  }

  // --- E2E over Socket Mode ------------------------------------------------

  private def sse(events: String*): String = events.map(e => s"data: $e\n\n").mkString

  test("socket mode E2E: identify, ACK envelope, DM turn, mention gating") {
    Slack.resetState()
    val acks   = new java.util.concurrent.ConcurrentLinkedQueue[String]()
    val posts  = new java.util.concurrent.ConcurrentLinkedQueue[(String, String)]() // (channel, text)

    val provider = HttpRoute.postText("/chat/completions").handler { _ =>
      HttpResponse.ok(sse(
        """{"choices":[{"delta":{"content":"pong"}}]}""",
        """{"choices":[{"delta":{},"finish_reason":"stop"}]}""", "[DONE]"))
    }
    // Slack Web API: auth.test (identity) + chat.postMessage (capture).
    val authTest = HttpRoute.postText("/api/auth.test").handler { _ =>
      HttpResponse.ok("""{"ok":true,"user_id":"UBOT"}""")
    }
    val postMsg = HttpRoute.postText("/api/chat.postMessage").handler { req =>
      Jx.parse(req.fields.body).foreach { v =>
        posts.add(((v / "channel").asStr.getOrElse(""), (v / "text").asStr.getOrElse("")))
      }
      HttpResponse.ok("""{"ok":true,"ts":"111.222"}""")
    }

    def event(ts: String, channel: String, user: String, text: String, chanType: String): String =
      Jx.render(Jx.obj("type" -> Jx.str("events_api"), "envelope_id" -> Jx.str(s"env-$ts"),
        "payload" -> Jx.obj("event" -> Jx.obj(
          "type" -> Jx.str("message"), "ts" -> Jx.str(ts), "channel" -> Jx.str(channel),
          "user" -> Jx.str(user), "text" -> Jx.str(text), "channel_type" -> Jx.str(chanType)))))

    val socket = HttpHandler.webSocket("/socket") { (_, ws) =>
      def put(j: String) = ws.put(HttpWebSocket.Payload.Text(j))
      // Capture ACK frames the connector sends back.
      def readAcks: Unit < (Async & kyo.Abort[Closed]) =
        ws.take().map {
          case HttpWebSocket.Payload.Text(t) => Sync.defer(acks.add(t)).andThen(readAcks)
          case _                             => readAcks
        }
      for
        _ <- put("""{"type":"hello"}""")
        _ <- Fiber.initUnscoped(Abort.run[Closed](readAcks))
        _ <- put(event("1", "D100", "U42", "hi there", "im"))              // DM → answered
        _ <- put(event("2", "C200", "U42", "no mention", "channel"))       // channel, no mention → ignored
        _ <- put(event("3", "C200", "U42", "<@UBOT> hello", "channel"))    // channel mention → answered
        _ <- put(event("4", "D100", "U99", "hi", "im"))                    // unauthorized user
        _ <- ws.onPeerClose
      yield ()
    }

    Slack.reconnectEnabled = true
    try
      run {
        Abort.run[HttpBindException] {
          for
            ps <- HttpServer.init(0, "127.0.0.1")(provider)
            ss <- HttpServer.init(0, "127.0.0.1")(authTest, postMsg, socket)
          yield (ps.port, ss.port)
        }.map {
          case Result.Success((providerPort, slackPort)) =>
            val home = java.nio.file.Files.createTempDirectory("apollo-slack-home")
            java.nio.file.Files.createDirectories(home.resolve("scala-state").resolve("sessions"))
            val config = ApolloConfig(Absent, EnvChain(Map(
              "SLACK_ALLOWED_USERS" -> "U42",
              "SLACK_API_BASE"      -> s"http://127.0.0.1:$slackPort/api",
              "SLACK_SOCKET_URL"    -> s"ws://127.0.0.1:$slackPort/socket"
            )), ApolloPaths(home))
            val runtime = ResolvedRuntime(
              providerSlug = "custom", displayName = "fake", model = "m",
              baseUrl = s"http://127.0.0.1:$providerPort", apiKey = Present("k"),
              apiMode = ApiMode.ChatCompletions, headers = Map.empty, profile = Absent,
              reasoning = Absent, maxTokens = Absent, contextLength = Absent, streaming = true)
            val hub = new SessionHub(config, ApolloPaths(home), runtime)
            Slack.services("xoxb-test", "xapp-test", config, hub).map { fibers =>
              Kyo.foreach(fibers)(f => Fiber.initUnscoped(f)).map { _ =>
                import scala.jdk.CollectionConverters.*
                // Wait for ALL posts the assertions below check (the old condition
                // returned after a single D100 post, racing the second one).
                def settled: Boolean =
                  val b = posts.asScala.toList
                  b.contains(("D100", "pong")) &&
                    b.contains(("C200", "pong")) &&
                    b.exists((ch, t) => ch == "D100" && t.startsWith("Not authorized"))
                def await(ms: Long): Unit < (Sync & Async) =
                  if settled || ms <= 0 then () else Async.sleep(100.millis).andThen(await(ms - 100))
                await(20000)
              }
            }
          case other => throw new AssertionError(s"bind failed: $other")
        }
      }
    finally Slack.reconnectEnabled = false

    import scala.jdk.CollectionConverters.*
    val bodies = posts.asScala.toList
    // DM answered; channel mention answered.
    assert(bodies.contains(("D100", "pong")), bodies.toString)
    assert(bodies.contains(("C200", "pong")), bodies.toString)
    // Unauthorized DM got the guidance, not a turn.
    assert(bodies.exists((ch, t) => ch == "D100" && t.startsWith("Not authorized")), bodies.toString)
    // Every events_api envelope was ACKed with its envelope_id.
    val ackText = acks.asScala.toList.mkString(" ")
    assert(ackText.contains("env-1") && ackText.contains("env-3"), ackText)
  }
end SlackSuite

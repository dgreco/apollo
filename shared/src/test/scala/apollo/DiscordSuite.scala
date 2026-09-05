package apollo.gateway

import apollo.config.{ApolloConfig, ApolloPaths, EnvChain}
import apollo.provider.{ApiMode, ResolvedRuntime}
import apollo.util.Jx
import apollo.util.Jx.*
import kyo.*
import kyo.Structure.Value

/** Discord connector: allowlist/chunking unit tests plus a full-stack E2E —
  * a fake Discord gateway (real websocket over localhost) + fake REST + a
  * fake chat-completions provider, driving a real SessionHub end to end.
  */
class DiscordSuite extends munit.FunSuite:

  private def run[A](v: A < (Sync & Async & Scope)): A =
    import AllowUnsafe.embrace.danger
    KyoApp.Unsafe.runAndBlock(60.seconds)(Scope.run(v)).getOrThrow

  // --- unit ----------------------------------------------------------------

  test("cleanId strips mention wrappers and user: prefixes") {
    assertEquals(Discord.cleanId("123"), "123")
    assertEquals(Discord.cleanId("<@123>"), "123")
    assertEquals(Discord.cleanId("<@!123>"), "123")
    assertEquals(Discord.cleanId("user:123"), "123")
  }

  test("authorizedUser fails closed and honors each admission path") {
    // Nothing configured → deny (the upstream surprise default).
    assert(!Discord.authorizedUser(Set.empty, Set.empty, false, "42", isDm = true, Set("500")))
    // Allow-all flag.
    assert(Discord.authorizedUser(Set.empty, Set.empty, true, "42", isDm = true, Set("500")))
    // Channel-scoped access admits guild traffic only, never DMs.
    assert(Discord.authorizedUser(Set.empty, Set("general"), false, "42", isDm = false, Set("600", "general")))
    assert(!Discord.authorizedUser(Set.empty, Set("general"), false, "42", isDm = true, Set("500")))
    // A user allowlist takes over entirely.
    assert(Discord.authorizedUser(Set("42"), Set.empty, false, "42", isDm = true, Set.empty))
    assert(!Discord.authorizedUser(Set("42"), Set.empty, true, "43", isDm = true, Set.empty))
    assert(Discord.authorizedUser(Set("*"), Set.empty, false, "43", isDm = true, Set.empty))
  }

  test("split respects the 2000 limit, prefers newlines, caps at 8 messages") {
    assertEquals(Discord.split("short"), List("short"))
    val lines = (1 to 300).map(i => s"line $i is somewhat long to fill space").mkString("\n")
    val chunks = Discord.split(lines)
    assert(chunks.forall(_.length <= Discord.maxMessage), chunks.map(_.length))
    assert(chunks.length >= 2)
    // Newline-preferring: no chunk should start mid-word for this input.
    assert(chunks.tail.forall(_.startsWith("line")), chunks.map(_.take(12)))
    val huge = "x" * (Discord.maxMessage * 12)
    val capped = Discord.split(huge)
    assertEquals(capped.length, 8)
    assert(capped.last.contains("Response truncated"), capped.last.takeRight(200))
    assert(capped.last.length <= Discord.maxMessage)
  }

  // --- E2E -----------------------------------------------------------------

  private def sse(events: String*): String = events.map(e => s"data: $e\n\n").mkString

  test("gateway E2E: identify, DM turn, mention gating, reset, auth denial") {
    Discord.resetState()
    val identifies = new java.util.concurrent.ConcurrentLinkedQueue[Value]()
    val sent       = new java.util.concurrent.ConcurrentLinkedQueue[(String, Value)]()

    val provider = HttpRoute.postText("/chat/completions").handler { _ =>
      HttpResponse.ok(sse(
        """{"choices":[{"delta":{"content":"pong"}}]}""",
        """{"choices":[{"delta":{},"finish_reason":"stop"}]}""",
        "[DONE]"
      ))
    }
    def restRoute(channel: String) =
      HttpRoute.postText(s"/channels/$channel/messages").handler { req =>
        Jx.parse(req.fields.body).foreach(v => sent.add(channel -> v))
        HttpResponse.ok("""{"id":"sent"}""")
      }
    def typingRoute(channel: String) =
      HttpRoute.postText(s"/channels/$channel/typing").handler(_ => HttpResponse.ok("{}"))

    def msg(id: String, channel: String, author: String, content: String,
            guild: Maybe[String], mentionsSelf: Boolean): String =
      Jx.render(Jx.obj("op" -> Jx.num(0L), "t" -> Jx.str("MESSAGE_CREATE"), "s" -> Jx.num(1L),
        "d" -> Jx.objOf(
          "id"         -> Present(Jx.str(id)),
          "channel_id" -> Present(Jx.str(channel)),
          "guild_id"   -> guild.map(Jx.str),
          "type"       -> Present(Jx.num(0L)),
          "content"    -> Present(Jx.str(content)),
          "author"     -> Present(Jx.obj("id" -> Jx.str(author), "username" -> Jx.str(s"user$author"))),
          "mentions"   -> Present(if mentionsSelf then Jx.arr(Jx.obj("id" -> Jx.str("99"))) else Jx.arr())
        )))

    val gateway = HttpHandler.webSocket("/gateway") { (_, ws) =>
      def put(json: String) = ws.put(HttpWebSocket.Payload.Text(json))
      for
        _ <- put("""{"op":10,"d":{"heartbeat_interval":60000}}""")
        idFrame <- ws.take()
        _ <- Sync.defer {
               idFrame match
                 case HttpWebSocket.Payload.Text(t) => Jx.parse(t).foreach(v => identifies.add(v))
                 case _                             => ()
             }
        _ <- put("""{"op":0,"t":"READY","s":0,"d":{"user":{"id":"99","username":"apollo"},"session_id":"s1","resume_gateway_url":"ws://unused"}}""")
        _ <- put("""{"op":0,"t":"GUILD_CREATE","s":1,"d":{"id":"700","channels":[{"id":"600","name":"general"}]}}""")
        _ <- put(msg("m1", "500", "42", "hello there", Absent, mentionsSelf = false))         // DM
        _ <- put(msg("m2", "600", "42", "no mention here", Present("700"), mentionsSelf = false)) // ignored
        _ <- put(msg("m3", "600", "42", "<@99> ping me", Present("700"), mentionsSelf = true))    // guild mention
        _ <- put(msg("m4", "500", "42", "/reset", Absent, mentionsSelf = false))              // command
        _ <- put(msg("m5", "500", "43", "hi", Absent, mentionsSelf = false))                  // unauthorized
        _ <- ws.onPeerClose
      yield ()
    }

    Discord.reconnectEnabled = true
    try
      run {
        Abort.run[HttpBindException] {
          for
            providerServer <- HttpServer.init(0, "127.0.0.1")(provider)
            discordServer  <- HttpServer.init(0, "127.0.0.1")(
                                gateway, restRoute("500"), restRoute("600"),
                                typingRoute("500"), typingRoute("600"))
          yield (providerServer.port, discordServer.port)
        }.map {
          case Result.Success((providerPort, discordPort)) =>
            val home = java.nio.file.Files.createTempDirectory("apollo-discord-home")
            java.nio.file.Files.createDirectories(home.resolve("scala-state").resolve("sessions"))
            val config = ApolloConfig(Absent, EnvChain(Map(
              "DISCORD_ALLOWED_USERS" -> "42",
              "DISCORD_REACTIONS"     -> "false",
              "DISCORD_API_BASE"      -> s"http://127.0.0.1:$discordPort",
              "DISCORD_GATEWAY_URL"   -> s"ws://127.0.0.1:$discordPort/gateway"
            )), ApolloPaths(home))
            val runtime = ResolvedRuntime(
              providerSlug = "custom", displayName = "fake", model = "fake-model",
              baseUrl = s"http://127.0.0.1:$providerPort", apiKey = Present("k"),
              apiMode = ApiMode.ChatCompletions, headers = Map.empty, profile = Absent,
              reasoning = Absent, maxTokens = Absent, contextLength = Absent, streaming = true
            )
            val hub = new SessionHub(config, ApolloPaths(home), runtime)
            Discord.services("test-token", config, hub).map { fibers =>
              Kyo.foreach(fibers)(f => Fiber.initUnscoped(f)).map { _ =>
                // Poll until the four expected outcomes landed.
                def settled: Boolean =
                  import scala.jdk.CollectionConverters.*
                  val bodies = sent.asScala.toList.map((ch, v) => (ch, (v / "content").asStr.getOrElse("")))
                  bodies.count((_, c) => c == "pong") >= 2 &&
                    bodies.exists((ch, c) => ch == "500" && c == "Conversation cleared.") &&
                    bodies.exists((ch, c) => ch == "500" && c.startsWith("Not authorized"))
                def await(remainingMs: Long): Unit < (Sync & Async) =
                  if settled || remainingMs <= 0 then ()
                  else Async.sleep(100.millis).andThen(await(remainingMs - 100))
                await(20000)
              }
            }
          case other => throw new AssertionError(s"bind failed: $other")
        }
      }
    finally Discord.reconnectEnabled = false

    import scala.jdk.CollectionConverters.*
    val id = identifies.asScala.toList
    assert(id.nonEmpty, "no identify received")
    assertEquals((id.head / "op").asLong, Present(2L))
    assertEquals((id.head / "d" / "token").asStr, Present("test-token"))
    assert((id.head / "d" / "intents").asLong.exists(i => (i & 32768L) != 0L), "MESSAGE_CONTENT intent missing")

    val bodies = sent.asScala.toList.map((ch, v) =>
      (ch, (v / "content").asStr.getOrElse(""), (v / "message_reference" / "message_id").asStr))
    // DM turn answered with a reply-reference to the triggering message.
    assert(bodies.exists((ch, c, ref) => ch == "500" && c == "pong" && ref == Present("m1")), bodies)
    // Guild mention answered; the un-mentioned guild message was not.
    assert(bodies.exists((ch, c, ref) => ch == "600" && c == "pong" && ref == Present("m3")), bodies)
    assert(!bodies.exists((_, c, ref) => ref == Present("m2")), bodies)
    // /reset handled inline; unauthorized DM got the guidance.
    assert(bodies.exists((ch, c, _) => ch == "500" && c == "Conversation cleared."), bodies)
    assert(bodies.exists((ch, c, _) => ch == "500" && c.startsWith("Not authorized")), bodies)
  }

  test("streaming E2E: the reply is posted once and edited live") {
    Discord.resetState()
    val posts = new java.util.concurrent.ConcurrentLinkedQueue[String]()
    val edits = new java.util.concurrent.ConcurrentLinkedQueue[String]()

    // A provider that streams several content deltas.
    val provider = HttpRoute.postText("/chat/completions").handler { _ =>
      HttpResponse.ok(sse(
        """{"choices":[{"delta":{"content":"Hel"}}]}""",
        """{"choices":[{"delta":{"content":"lo, wor"}}]}""",
        """{"choices":[{"delta":{"content":"ld!"}}]}""",
        """{"choices":[{"delta":{},"finish_reason":"stop"}]}""",
        "[DONE]"))
    }
    val postRoute = HttpRoute.postText("/channels/500/messages").handler { req =>
      Jx.parse(req.fields.body).foreach(v => (v / "content").asStr.foreach(posts.add))
      HttpResponse.ok("""{"id":"msg-1"}""")
    }
    val patchRoute = HttpRoute.patchText("/channels/500/messages/msg-1").handler { req =>
      Jx.parse(req.fields.body).foreach(v => (v / "content").asStr.foreach(edits.add))
      HttpResponse.ok("""{"id":"msg-1"}""")
    }
    val typingRoute = HttpRoute.postText("/channels/500/typing").handler(_ => HttpResponse.ok("{}"))

    def dm(id: String) =
      Jx.render(Jx.obj("op" -> Jx.num(0L), "t" -> Jx.str("MESSAGE_CREATE"), "s" -> Jx.num(1L),
        "d" -> Jx.obj("id" -> Jx.str(id), "channel_id" -> Jx.str("500"), "type" -> Jx.num(0L),
          "content" -> Jx.str("hi"),
          "author" -> Jx.obj("id" -> Jx.str("42"), "username" -> Jx.str("u")),
          "mentions" -> Jx.arr())))

    val gateway = HttpHandler.webSocket("/gateway") { (_, ws) =>
      def put(j: String) = ws.put(HttpWebSocket.Payload.Text(j))
      for
        _ <- put("""{"op":10,"d":{"heartbeat_interval":60000}}""")
        _ <- ws.take()
        _ <- put("""{"op":0,"t":"READY","s":0,"d":{"user":{"id":"99","username":"apollo"},"session_id":"s1","resume_gateway_url":"ws://unused"}}""")
        _ <- put(dm("d1"))
        _ <- ws.onPeerClose
      yield ()
    }

    Discord.reconnectEnabled = true
    try
      run {
        Abort.run[HttpBindException] {
          for
            ps <- HttpServer.init(0, "127.0.0.1")(provider)
            ds <- HttpServer.init(0, "127.0.0.1")(gateway, postRoute, patchRoute, typingRoute)
          yield (ps.port, ds.port)
        }.map {
          case Result.Success((providerPort, discordPort)) =>
            val home = java.nio.file.Files.createTempDirectory("apollo-discord-stream")
            java.nio.file.Files.createDirectories(home.resolve("scala-state").resolve("sessions"))
            // streaming.* lives in config.yaml; creds/URLs in env.
            val config = ApolloConfig(
              Present(apollo.config.Yaml.parse(
                "streaming: {enabled: true, edit_interval: 0, buffer_threshold: 1}"
              ).getOrElse(throw new AssertionError("yaml"))),
              EnvChain(Map(
                "DISCORD_ALLOWED_USERS" -> "42",
                "DISCORD_REACTIONS"     -> "false",
                "DISCORD_API_BASE"      -> s"http://127.0.0.1:$discordPort",
                "DISCORD_GATEWAY_URL"   -> s"ws://127.0.0.1:$discordPort/gateway"
              )), ApolloPaths(home))
            val runtime = ResolvedRuntime(
              providerSlug = "custom", displayName = "fake", model = "fake-model",
              baseUrl = s"http://127.0.0.1:$providerPort", apiKey = Present("k"),
              apiMode = ApiMode.ChatCompletions, headers = Map.empty, profile = Absent,
              reasoning = Absent, maxTokens = Absent, contextLength = Absent, streaming = true)
            val hub = new SessionHub(config, ApolloPaths(home), runtime)
            Discord.services("test-token", config, hub).map { fibers =>
              Kyo.foreach(fibers)(f => Fiber.initUnscoped(f)).map { _ =>
                def settled: Boolean =
                  import scala.jdk.CollectionConverters.*
                  posts.asScala.nonEmpty && edits.asScala.exists(_ == "Hello, world!")
                def await(ms: Long): Unit < (Sync & Async) =
                  if settled || ms <= 0 then () else Async.sleep(100.millis).andThen(await(ms - 100))
                await(20000)
              }
            }
          case other => throw new AssertionError(s"bind failed: $other")
        }
      }
    finally Discord.reconnectEnabled = false

    import scala.jdk.CollectionConverters.*
    // Exactly one initial post; the final edit carries the complete text.
    assertEquals(posts.asScala.toList.size, 1, posts.asScala.toList.toString)
    assert(edits.asScala.toList.contains("Hello, world!"), edits.asScala.toList.toString)
  }
end DiscordSuite
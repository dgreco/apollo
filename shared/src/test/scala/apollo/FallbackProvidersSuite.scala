package apollo.agent

import apollo.config.{ApolloConfig, ApolloPaths, EnvChain, Yaml}
import apollo.core.*
import apollo.provider.{ApiMode, ResolvedRuntime}
import apollo.session.SessionStore
import apollo.tools.*
import apollo.util.Jx
import apollo.util.Jx.*
import kyo.*

/** `fallback_providers`: a turn fails over to the next configured provider
  * when the primary errors, and surfaces an error only when all fail. */
class FallbackProvidersSuite extends munit.FunSuite:

  private def run[A](v: A < (Sync & Async & Scope)): A =
    import AllowUnsafe.embrace.danger
    KyoApp.Unsafe.runAndBlock(30.seconds)(Scope.run(v)).getOrThrow

  private def okBody(text: String) = Jx.render(Jx.obj(
    "choices" -> Jx.arr(Jx.obj(
      "message" -> Jx.obj("role" -> Jx.str("assistant"), "content" -> Jx.str(text)),
      "finish_reason" -> Jx.str("stop"))),
    "usage" -> Jx.obj("prompt_tokens" -> Jx.num(1L), "completion_tokens" -> Jx.num(1L))))

  /** Runs one turn against a primary route and a fallback route (both under
    * one server); `primaryOk`/`fallbackOk` pick 200-with-body vs 500.
    * Returns (turn result, primaryHits, fallbackHits). `fallbackConfigured`
    * toggles whether `fallback_providers` is set at all.
    */
  private def scenario(primaryOk: Boolean, fallbackOk: Boolean, fallbackConfigured: Boolean)
      : (TurnResult, Int, Int) =
    val primaryHits  = new java.util.concurrent.atomic.AtomicInteger(0)
    val fallbackHits = new java.util.concurrent.atomic.AtomicInteger(0)
    def route(path: String, ok: Boolean, counter: java.util.concurrent.atomic.AtomicInteger, text: String) =
      HttpRoute.postText(s"$path/chat/completions").handler { _ =>
        counter.incrementAndGet()
        if ok then HttpResponse.ok(okBody(text)).setHeader("content-type", "application/json")
        else HttpResponse(HttpStatus.InternalServerError).addField("body", "boom")
      }
    run {
      Abort.run[HttpBindException](HttpServer.init(0, "127.0.0.1")(
        route("/primary", primaryOk, primaryHits, "from-primary"),
        route("/fallback", fallbackOk, fallbackHits, "from-fallback")
      )).map {
        case Result.Success(server) =>
          val base = s"http://127.0.0.1:${server.port}"
          val home = java.nio.file.Files.createTempDirectory("apollo-fb-home")
          java.nio.file.Files.createDirectories(home.resolve("scala-state").resolve("sessions"))
          val paths = ApolloPaths(home)
          val yaml =
            s"""agent: {api_max_retries: 1}
               |model: {streaming: false}
               |providers:
               |  fb:
               |    base_url: "$base/fallback"
               |    api_mode: chat_completions
               |    api_key: k
               |    model: fb-model
               |${if fallbackConfigured then "fallback_providers: [custom:fb]" else ""}
               |""".stripMargin
          val config = ApolloConfig(
            Present(Yaml.parse(yaml).getOrElse(throw new AssertionError("yaml"))),
            EnvChain(Map.empty), paths)
          val primary = ResolvedRuntime(
            providerSlug = "custom", displayName = "primary", model = "primary-model",
            baseUrl = s"$base/primary", apiKey = Present("k"), apiMode = ApiMode.ChatCompletions,
            headers = Map.empty, profile = Absent, reasoning = Absent, maxTokens = Absent,
            contextLength = Absent, streaming = false)
          val store = new SessionStore(paths)
          AtomicRef.init(List.empty[TodoItem]).map { todo =>
            val ctx = ToolContext(
              config = config, paths = paths, cwd = home, platform = "cli", sessionId = "fb",
              approvals = new ApprovalService(config, paths, "cli", oneShot = false, yoloFlag = true),
              ui = apollo.cli.UnattendedToolUi, todo = todo,
              skills = new apollo.skills.SkillStore(config, paths))
            val flag = new java.util.concurrent.atomic.AtomicBoolean(false)
            val agent = new Agent(primary, ctx, store, "fb", Present(3), flag)
            agent.runTurn(Message.user("hi"), "SYS", Nil, TurnCallbacks())
              .map(r => (r, primaryHits.get(), fallbackHits.get()))
          }
        case other => throw new AssertionError(s"bind failed: $other")
      }
    }

  test("primary failure fails over to a healthy fallback provider") {
    val (r, primaryHits, fallbackHits) = scenario(primaryOk = false, fallbackOk = true, fallbackConfigured = true)
    assertEquals(r.exitReason, "text_response")
    assertEquals(r.finalResponse, "from-fallback")
    assert(primaryHits >= 1, s"primary not tried: $primaryHits")
    assert(fallbackHits >= 1, s"fallback not tried: $fallbackHits")
  }

  test("all providers failing surfaces an error") {
    val (r, _, fallbackHits) = scenario(primaryOk = false, fallbackOk = false, fallbackConfigured = true)
    assert(r.exitReason.startsWith("error"), r.exitReason)
    assert(fallbackHits >= 1, "fallback should still have been attempted")
  }

  test("no fallback configured: primary failure surfaces directly (no failover)") {
    val (r, primaryHits, fallbackHits) = scenario(primaryOk = false, fallbackOk = true, fallbackConfigured = false)
    assert(r.exitReason.startsWith("error"), r.exitReason)
    assertEquals(fallbackHits, 0, "fallback must not be hit when unconfigured")
    assert(primaryHits >= 1)
  }

  test("healthy primary is used and the fallback is never touched") {
    val (r, primaryHits, fallbackHits) = scenario(primaryOk = true, fallbackOk = true, fallbackConfigured = true)
    assertEquals(r.finalResponse, "from-primary")
    assert(primaryHits >= 1)
    assertEquals(fallbackHits, 0)
  }
end FallbackProvidersSuite

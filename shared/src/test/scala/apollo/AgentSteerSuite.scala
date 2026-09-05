package apollo.agent

import apollo.config.{ApolloConfig, ApolloPaths, EnvChain, Yaml}
import apollo.core.*
import apollo.provider.{ApiMode, ResolvedRuntime}
import apollo.session.SessionStore
import apollo.tools.*
import apollo.util.Jx
import apollo.util.Jx.*
import kyo.*

/** End-to-end: a real `Agent` against a mock chat-completions server. Turn 1
  * returns a tool call; a steer message enqueued via `agent.steer` must be
  * injected as a user message and appear in the SECOND request to the model. */
class AgentSteerSuite extends munit.FunSuite:

  private def run[A](v: A < (Sync & Async & Scope)): A =
    import AllowUnsafe.embrace.danger
    KyoApp.Unsafe.runAndBlock(30.seconds)(Scope.run(v)).getOrThrow

  test("a queued steer is injected after the tool round and reaches the next request") {
    val bodies = new java.util.concurrent.ConcurrentLinkedQueue[String]()
    val calls  = new java.util.concurrent.atomic.AtomicInteger(0)

    val route = HttpRoute.postText("/chat/completions").handler { req =>
      bodies.add(req.fields.body)
      val first = calls.getAndIncrement() == 0
      val message =
        if first then
          Jx.obj("role" -> Jx.str("assistant"), "content" -> Jx.str(""),
            "tool_calls" -> Jx.arr(Jx.obj(
              "id" -> Jx.str("c1"), "type" -> Jx.str("function"),
              "function" -> Jx.obj("name" -> Jx.str("noop"), "arguments" -> Jx.str("{}")))))
        else
          Jx.obj("role" -> Jx.str("assistant"), "content" -> Jx.str("done"))
      val finish = if first then "tool_calls" else "stop"
      HttpResponse.ok(Jx.render(Jx.obj(
        "choices" -> Jx.arr(Jx.obj("message" -> message, "finish_reason" -> Jx.str(finish))),
        "usage" -> Jx.obj("prompt_tokens" -> Jx.num(1L), "completion_tokens" -> Jx.num(1L))
      ))).setHeader("content-type", "application/json")
    }

    run {
      Abort.run[HttpBindException](HttpServer.init(0, "127.0.0.1")(route)).map {
        case Result.Success(server) =>
          val home = java.nio.file.Files.createTempDirectory("apollo-steer-home")
          java.nio.file.Files.createDirectories(home.resolve("scala-state").resolve("sessions"))
          val paths  = ApolloPaths(home)
          val config = ApolloConfig(Absent, EnvChain(Map.empty), paths)
          val runtime = ResolvedRuntime(
            providerSlug = "custom", displayName = "mock", model = "mock-model",
            baseUrl = s"http://127.0.0.1:${server.port}", apiKey = Present("k"),
            apiMode = ApiMode.ChatCompletions, headers = Map.empty, profile = Absent,
            reasoning = Absent, maxTokens = Absent, contextLength = Absent, streaming = false)
          val store = new SessionStore(paths)
          AtomicRef.init(List.empty[TodoItem]).map { todo =>
            val ctx = ToolContext(
              config = config, paths = paths, cwd = home, platform = "cli", sessionId = "steer-test",
              approvals = new ApprovalService(config, paths, "cli", oneShot = false, yoloFlag = true),
              ui = apollo.cli.UnattendedToolUi, todo = todo,
              skills = new apollo.skills.SkillStore(config, paths))
            val flag  = new java.util.concurrent.atomic.AtomicBoolean(false)
            val agent = new Agent(runtime, ctx, store, "steer-test", Present(5), flag)
            agent.steer("STEER-INJECT-XYZ") // queued before the run; drained after the tool round
            agent.runTurn(Message.user("hello"), "BASE-PROMPT", Nil, TurnCallbacks()).unit
          }
        case other => throw new AssertionError(s"bind failed: $other")
      }
    }

    import scala.jdk.CollectionConverters.*
    val reqs = bodies.asScala.toList
    assertEquals(reqs.length, 2, s"expected 2 model requests, got ${reqs.length}")
    // Request 1 (the tool-call turn) must NOT yet contain the steer.
    assert(!reqs(0).contains("STEER-INJECT-XYZ"), reqs(0))
    // Request 2 must contain the injected steer message.
    assert(reqs(1).contains("STEER-INJECT-XYZ"), reqs(1))
  }
end AgentSteerSuite

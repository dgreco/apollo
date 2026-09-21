package apollo.agent

import apollo.config.{ApolloConfig, ApolloPaths, EnvChain}
import apollo.core.*
import apollo.provider.{ApiMode, ResolvedRuntime}
import apollo.session.SessionStore
import apollo.tools.*
import apollo.util.Jx
import kyo.*

/** AutoReview: pure cadence/prompt + an E2E where the forked reviewer actually
  * calls the memory tool and a real file is written. */
class AutoReviewSuite extends munit.FunSuite:

  private def run[A](v: A < (Sync & Async & Scope)): A =
    import AllowUnsafe.embrace.danger
    KyoApp.Unsafe.runAndBlock(30.seconds)(Scope.run(v)).getOrThrow

  test("tick fires every `interval` turns and resets") {
    val (s1, d1) = AutoReview.tick(AutoReview.State(), 2); assert(!d1); assertEquals(s1.turnsSinceReview, 1)
    val (s2, d2) = AutoReview.tick(s1, 2); assert(d2); assertEquals(s2.turnsSinceReview, 0)
    val (_, d3)  = AutoReview.tick(AutoReview.State(), 1); assert(d3, "interval 1 fires immediately")
  }

  test("reviewToolNames keeps only the saving tools; instruction is conservative") {
    assertEquals(AutoReview.reviewToolNames(List("memory", "terminal", "skill_manage", "read_file")),
      List("memory", "skill_manage"))
    assertEquals(AutoReview.reviewToolNames(List("terminal", "read_file")), Nil)
    val ins = AutoReview.reviewInstruction(hasMemory = true, hasSkill = true)
    assert(ins.contains("memory"), ins)
    assert(ins.contains("skill_manage"), ins)
    assert(ins.contains("NONE"), ins)
    assert(AutoReview.reviewInstruction(hasMemory = true, hasSkill = false).contains("memory"))
  }

  test("a background review may add to memory but not remove from it") {
    val home   = java.nio.file.Files.createTempDirectory("apollo-review-mem")
    val paths  = ApolloPaths(home)
    val config = ApolloConfig(Absent, EnvChain(Map.empty), paths)
    import AllowUnsafe.embrace.danger
    val todo = KyoApp.Unsafe.runAndBlock(10.seconds)(AtomicRef.init(List.empty[TodoItem])).getOrThrow
    val live = ToolContext(
      config = config, paths = paths, cwd = home, platform = "cli", sessionId = "s",
      approvals = new ApprovalService(config, paths, "cli", oneShot = false, yoloFlag = true),
      ui = apollo.tools.UnattendedToolUi, todo = todo,
      skills = new apollo.skills.SkillStore(config, paths))
    val review = live.copy(platform = "auto-review", memoryDeletesAllowed = false)

    def call(ctx: ToolContext, json: String): (String, Boolean) =
      KyoApp.Unsafe.runAndBlock(10.seconds)(ToolRegistry.dispatch("memory", json, ctx)).getOrThrow

    val add = """{"target":"memory","action":"add","content":"keeper"}"""
    assertEquals(call(review, add)._2, false)                       // saving is fine
    val remove = """{"target":"memory","action":"remove","old_text":"keeper"}"""
    val (msg, isErr) = call(review, remove)
    assert(isErr, msg)
    assert(msg.contains("not remove entries"), msg)
    // Batched operations are gated the same way...
    val batch = """{"target":"memory","operations":[{"action":"remove","old_text":"keeper"}]}"""
    assert(call(review, batch)._2, "a batched remove slipped through")
    // ...and a normal, human-watched turn still removes.
    assertEquals(call(live, remove)._2, false)
    val text = new String(java.nio.file.Files.readAllBytes(paths.memoryMd), "UTF-8")
    assert(!text.contains("keeper"), s"remove did not apply in a live turn: $text")
  }

  test("E2E: the reviewer calls the memory tool and writes MEMORY.md") {
    val callN = new java.util.concurrent.atomic.AtomicInteger(0)
    val memArgs = Jx.render(Jx.obj(
      "target" -> Jx.str("memory"), "action" -> Jx.str("add"),
      "content" -> Jx.str("AUTOREVIEW-FACT: the user prefers tabs")))

    val route = HttpRoute.postText("/chat/completions").handler { _ =>
      val body =
        if callN.getAndIncrement() == 0 then
          Jx.obj(
            "choices" -> Jx.arr(Jx.obj(
              "message" -> Jx.obj("role" -> Jx.str("assistant"),
                "tool_calls" -> Jx.arr(Jx.obj(
                  "id" -> Jx.str("tc1"), "type" -> Jx.str("function"),
                  "function" -> Jx.obj("name" -> Jx.str("memory"), "arguments" -> Jx.str(memArgs))))),
              "finish_reason" -> Jx.str("tool_calls"))),
            "usage" -> Jx.obj("prompt_tokens" -> Jx.num(1L), "completion_tokens" -> Jx.num(1L)))
        else
          Jx.obj(
            "choices" -> Jx.arr(Jx.obj(
              "message" -> Jx.obj("role" -> Jx.str("assistant"), "content" -> Jx.str("done")),
              "finish_reason" -> Jx.str("stop"))),
            "usage" -> Jx.obj("prompt_tokens" -> Jx.num(1L), "completion_tokens" -> Jx.num(1L)))
      HttpResponse.ok(Jx.render(body)).setHeader("content-type", "application/json")
    }

    val home = java.nio.file.Files.createTempDirectory("apollo-autoreview")

    run {
      Abort.run[HttpBindException](HttpServer.init(0, "127.0.0.1")(route)).map {
        case Result.Success(server) =>
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
              config = config, paths = paths, cwd = home, platform = "cli", sessionId = "src",
              approvals = new ApprovalService(config, paths, "cli", oneShot = false, yoloFlag = true),
              ui = apollo.tools.UnattendedToolUi, todo = todo,
              skills = new apollo.skills.SkillStore(config, paths))
            val history = List(Message.user("please refactor X"), Message.assistant("done, note: you like tabs"))
            AutoReview.runOnce(runtime, ctx, store, history, List("memory", "skill_manage"))
          }
        case other => throw new AssertionError(s"bind failed: $other")
      }
    }

    val memFile = ApolloPaths(home).memoryMd
    assert(java.nio.file.Files.exists(memFile), s"MEMORY.md not written at $memFile")
    val text = new String(java.nio.file.Files.readAllBytes(memFile), "UTF-8")
    assert(text.contains("AUTOREVIEW-FACT"), s"memory content missing: $text")
  }
end AutoReviewSuite

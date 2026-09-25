// SPDX-FileCopyrightText: 2026 David Greco <greco@acm.org>
//
// SPDX-License-Identifier: Apache-2.0

package apollo.agent

import apollo.config.{ApolloConfig, ApolloPaths, EnvChain, Yaml}
import apollo.core.*
import apollo.provider.{ApiMode, ResolvedRuntime}
import apollo.session.SessionStore
import apollo.tools.*
import apollo.util.Jx
import apollo.util.Jx.*
import kyo.*

/** End-to-end: drive a real `Agent` against a mock chat-completions server
  * and confirm the self-improvement nudge lands in the system prompt sent to
  * the model on the right turns (and nowhere else).
  */
class NudgeIntegrationSuite extends munit.FunSuite:

  private def run[A](v: A < (Sync & Async & Scope)): A =
    import AllowUnsafe.embrace.danger
    KyoApp.Unsafe.runAndBlock(30.seconds)(Scope.run(v)).getOrThrow

  test("nudge reaches the model's system prompt at the configured cadence") {
    val systemPrompts = new java.util.concurrent.ConcurrentLinkedQueue[String]()

    val route = HttpRoute.postText("/chat/completions").handler { req =>
      val body = Jx.parse(req.fields.body).getOrElse(Jx.obj())
      val sys = body.field("messages").asArr.getOrElse(Chunk.empty).headOption match
        case Some(m) => (m / "content").asStr.getOrElse("")
        case None    => ""
      systemPrompts.add(sys)
      HttpResponse.ok(Jx.render(Jx.obj(
        "choices" -> Jx.arr(Jx.obj(
          "message" -> Jx.obj("role" -> Jx.str("assistant"), "content" -> Jx.str("ok")),
          "finish_reason" -> Jx.str("stop"))),
        "usage" -> Jx.obj("prompt_tokens" -> Jx.num(1L), "completion_tokens" -> Jx.num(1L))
      ))).setHeader("content-type", "application/json")
    }

    run {
      Abort.run[HttpBindException](HttpServer.init(0, "127.0.0.1")(route)).map {
        case Result.Success(server) =>
          val home = java.nio.file.Files.createTempDirectory("apollo-nudge-home")
          java.nio.file.Files.createDirectories(home.resolve("scala-state").resolve("sessions"))
          val paths = ApolloPaths(home)
          val config = ApolloConfig(
            Present(Yaml.parse(
              """memory: {nudge_interval: 2}
                |skills: {creation_nudge_interval: 3}
                |""".stripMargin).getOrElse(throw new AssertionError("yaml"))),
            EnvChain(Map.empty), paths)
          val runtime = ResolvedRuntime(
            providerSlug = "custom", displayName = "mock", model = "mock-model",
            baseUrl = s"http://127.0.0.1:${server.port}", apiKey = Present("k"),
            apiMode = ApiMode.ChatCompletions, headers = Map.empty, profile = Absent,
            reasoning = Absent, maxTokens = Absent, contextLength = Absent, streaming = false)
          val store = new SessionStore(paths)
          AtomicRef.init(List.empty[TodoItem]).map { todo =>
            val ctx = ToolContext(
              config = config, paths = paths, cwd = home, platform = "cli", sessionId = "nudge-test",
              approvals = new ApprovalService(config, paths, "cli", oneShot = false, yoloFlag = true),
              ui = apollo.tools.UnattendedToolUi, todo = todo,
              skills = new apollo.skills.SkillStore(config, paths))
            val flag = new java.util.concurrent.atomic.AtomicBoolean(false)
            val agent = new Agent(runtime, ctx, store, "nudge-test", Present(5), flag)
            val tools = List("memory", "skill_manage")
            def turn(): Unit < (Sync & Async) =
              agent.runTurn(Message.user("hello"), "BASE-PROMPT", tools, TurnCallbacks()).unit
            turn().andThen(turn()).andThen(turn())
          }
        case other => throw new AssertionError(s"bind failed: $other")
      }
    }

    import scala.jdk.CollectionConverters.*
    val prompts = systemPrompts.asScala.toList
    assertEquals(prompts.length, 3, prompts.toString)
    // Turn 1: base prompt only.
    assert(!prompts(0).contains("[self-improvement]"), prompts(0))
    assert(prompts(0).startsWith("BASE-PROMPT"), prompts(0))
    // Turn 2: memory nudge (interval 2) fires and is appended.
    assert(prompts(1).contains("saved to memory"), prompts(1))
    assert(prompts(1).startsWith("BASE-PROMPT"), prompts(1))
    // Turn 3: skill nudge (interval 3) fires; memory does not (reset on turn 2).
    assert(prompts(2).contains("as a skill"), prompts(2))
    assert(!prompts(2).contains("saved to memory"), prompts(2))
  }
end NudgeIntegrationSuite

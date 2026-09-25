// SPDX-FileCopyrightText: 2026 David Greco <greco@acm.org>
//
// SPDX-License-Identifier: Apache-2.0

package apollo.gateway

import apollo.config.{ApolloConfig, ApolloPaths, EnvChain}
import apollo.core.*
import apollo.provider.{ApiMode, ResolvedRuntime}
import apollo.session.{HandoffStore, SessionStore}
import apollo.util.Jx
import kyo.*

/** `/handoff` control channel: the record format, consume-once semantics, and
  * the gateway adopting a pending handoff into a new session (end-to-end
  * against a mock chat-completions server). */
class HandoffSuite extends munit.FunSuite:

  private def run[A](v: A < (Sync & Async & Scope)): A =
    import AllowUnsafe.embrace.danger
    KyoApp.Unsafe.runAndBlock(30.seconds)(Scope.run(v)).getOrThrow

  test("format/parse round-trip") {
    val s = HandoffStore.format("abc", 7.0)
    assertEquals(HandoffStore.parse(s), Present(HandoffStore.Handoff("abc", 7.0)))
    assertEquals(HandoffStore.parse("not json"), Absent)
    assertEquals(HandoffStore.parse("{}"), Absent) // missing session_id
  }

  test("write then consume returns the record exactly once") {
    val paths = ApolloPaths(java.nio.file.Files.createTempDirectory("apollo-handoff"))
    val (first, second) = run {
      HandoffStore.write(paths, "telegram", "sess-9", 123.0).andThen {
        HandoffStore.consume(paths, "telegram").map { a =>
          HandoffStore.consume(paths, "telegram").map(b => (a, b))
        }
      }
    }
    assertEquals(first, Present(HandoffStore.Handoff("sess-9", 123.0)))
    assertEquals(second, Absent) // consume-once
  }

  test("gateway adopts a pending handoff into a new session") {
    val bodies = new java.util.concurrent.ConcurrentLinkedQueue[String]()
    val provider = HttpRoute.postText("/chat/completions").handler { req =>
      bodies.add(req.fields.body)
      HttpResponse.ok(Jx.render(Jx.obj(
        "choices" -> Jx.arr(Jx.obj(
          "message" -> Jx.obj("role" -> Jx.str("assistant"), "content" -> Jx.str("ok")),
          "finish_reason" -> Jx.str("stop"))),
        "usage" -> Jx.obj("prompt_tokens" -> Jx.num(1L), "completion_tokens" -> Jx.num(1L))
      ))).setHeader("content-type", "application/json")
    }

    run {
      Abort.run[HttpBindException](HttpServer.init(0, "127.0.0.1")(provider)).map {
        case Result.Success(server) =>
          val home = java.nio.file.Files.createTempDirectory("apollo-handoff-gw")
          java.nio.file.Files.createDirectories(home.resolve("scala-state").resolve("sessions"))
          val paths  = ApolloPaths(home)
          val config = ApolloConfig(Absent, EnvChain(Map.empty), paths)
          val runtime = ResolvedRuntime(
            providerSlug = "custom", displayName = "mock", model = "mock-model",
            baseUrl = s"http://127.0.0.1:${server.port}", apiKey = Present("k"),
            apiMode = ApiMode.ChatCompletions, headers = Map.empty, profile = Absent,
            reasoning = Absent, maxTokens = Absent, contextLength = Absent, streaming = false)
          val store = new SessionStore(paths)
          // Seed a REPL session transcript and queue a handoff to telegram.
          store.rewriteTranscript("src-1", List(
            Message.user("HANDOFF-MARKER-42 what is the plan?"),
            Message.assistant("the earlier answer")
          )).andThen(HandoffStore.write(paths, "telegram", "src-1", 0.0)).andThen {
            val hub = new SessionHub(config, paths, runtime)
            val key = hub.sessionKey("telegram", "chat", "c1", Absent)
            hub.turn(key, "telegram", "hello from the platform").unit
          }
        case other => throw new AssertionError(s"bind failed: $other")
      }
    }

    import scala.jdk.CollectionConverters.*
    val reqs = bodies.asScala.toList
    assert(reqs.nonEmpty, "model was never called")
    // The handed-off history must be present in the request the gateway sent.
    assert(reqs.exists(_.contains("HANDOFF-MARKER-42")), reqs.mkString("\n---\n"))
  }
end HandoffSuite

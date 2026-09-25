// SPDX-FileCopyrightText: 2026 David Greco <greco@acm.org>
//
// SPDX-License-Identifier: Apache-2.0

package apollo.gateway

import apollo.config.{ApolloConfig, ApolloPaths, EnvChain}
import apollo.provider.{ApiMode, ResolvedRuntime}
import apollo.util.Jx
import kyo.*
import kyo.Structure.Value

/** Matrix connector: parseSync units + an E2E (fake homeserver + fake provider)
  * through a real SessionHub. */
class MatrixSuite extends munit.FunSuite:

  private def run[A](v: A < (Sync & Async & Scope)): A =
    import AllowUnsafe.embrace.danger
    KyoApp.Unsafe.runAndBlock(60.seconds)(Scope.run(v)).getOrThrow

  test("parseSync extracts next_batch + m.text messages, ignores non-text") {
    val json = Jx.parse(
      """{"next_batch":"s2","rooms":{"join":{"!r:hs":{"timeline":{"events":[
        {"type":"m.room.message","sender":"@u:hs","content":{"msgtype":"m.text","body":"hi"}},
        {"type":"m.room.message","sender":"@u:hs","content":{"msgtype":"m.image","url":"mxc://x"}},
        {"type":"m.reaction","sender":"@u:hs","content":{}}
      ]}}}}}""").getOrElse(Jx.obj())
    val (nb, msgs) = Matrix.parseSync(json)
    assertEquals(nb, Present("s2"))
    assertEquals(msgs, List(Matrix.RoomMsg("!r:hs", "@u:hs", "hi")))
    assertEquals(Matrix.parseSync(Jx.obj())._2, Nil)
  }

  test("E2E: a room message drives a turn and the reply is sent back") {
    val sends    = new java.util.concurrent.ConcurrentLinkedQueue[String]()
    val syncSeq  = new java.util.concurrent.atomic.AtomicInteger(0)

    val whoami = HttpRoute.getText("_matrix" / "client" / "v3" / "account" / "whoami").handler { _ =>
      HttpResponse.ok(Jx.render(Jx.obj("user_id" -> Jx.str("@bot:test")))).setHeader("content-type", "application/json")
    }
    val sync = HttpRoute.getText("_matrix" / "client" / "v3" / "sync").handler { _ =>
      // call 0 = initial (no since): empty; call 1: one message; after: empty.
      val body = syncSeq.getAndIncrement() match
        case 1 => """{"next_batch":"s2","rooms":{"join":{"!room:test":{"timeline":{"events":[
                     {"type":"m.room.message","sender":"@user:test","content":{"msgtype":"m.text","body":"hello matrix"}}]}}}}}"""
        case _ => """{"next_batch":"s1","rooms":{"join":{}}}"""
      HttpResponse.ok(body).setHeader("content-type", "application/json")
    }
    val send = HttpRoute.putText(
      "_matrix" / "client" / "v3" / "rooms" / kyo.Capture[String]("room") / "send" / "m.room.message" / kyo.Capture[String]("txn")
    ).handler { r =>
      sends.add(r.fields.body)
      HttpResponse.ok(Jx.render(Jx.obj("event_id" -> Jx.str("$1")))).setHeader("content-type", "application/json")
    }
    val provider = HttpRoute.postText("chat" / "completions").handler { _ =>
      HttpResponse.ok(Jx.render(Jx.obj(
        "choices" -> Jx.arr(Jx.obj("message" -> Jx.obj("role" -> Jx.str("assistant"), "content" -> Jx.str("ok")),
          "finish_reason" -> Jx.str("stop"))),
        "usage" -> Jx.obj("prompt_tokens" -> Jx.num(1L), "completion_tokens" -> Jx.num(1L))
      ))).setHeader("content-type", "application/json")
    }

    run {
      Abort.run[HttpBindException](HttpServer.init(0, "127.0.0.1")(whoami, sync, send, provider)).map {
        case Result.Success(server) =>
          val hs     = s"http://127.0.0.1:${server.port}"
          val home   = java.nio.file.Files.createTempDirectory("apollo-matrix")
          java.nio.file.Files.createDirectories(home.resolve("scala-state").resolve("sessions"))
          val config = ApolloConfig(Absent, EnvChain(Map(
            "MATRIX_HOMESERVER"     -> hs,
            "MATRIX_ACCESS_TOKEN"   -> "tok",
            "MATRIX_ALLOW_ALL_USERS" -> "1"
          )), ApolloPaths(home))
          val runtime = ResolvedRuntime(
            providerSlug = "custom", displayName = "mock", model = "m", baseUrl = hs,
            apiKey = Present("k"), apiMode = ApiMode.ChatCompletions, headers = Map.empty, profile = Absent,
            reasoning = Absent, maxTokens = Absent, contextLength = Absent, streaming = false)
          val hub = new SessionHub(config, ApolloPaths(home), runtime)
          Matrix.services(hs, "tok", config, hub).map { fibers =>
            Kyo.foreach(fibers)(f => Fiber.initUnscoped(f)).map { _ =>
              import scala.jdk.CollectionConverters.*
              def settled = sends.asScala.exists(_.contains("ok"))
              def await(ms: Long): Unit < (Sync & Async) =
                if settled || ms <= 0 then () else Async.sleep(150.millis).andThen(await(ms - 150))
              await(30000)
            }
          }
        case other => throw new AssertionError(s"bind failed: $other")
      }
    }

    import scala.jdk.CollectionConverters.*
    val bodies = sends.asScala.toList
    assert(bodies.exists(b => b.contains("\"m.text\"") && b.contains("ok")), bodies.toString)
  }
end MatrixSuite

// SPDX-FileCopyrightText: 2026 David Greco <greco@acm.org>
//
// SPDX-License-Identifier: Apache-2.0

package apollo

import apollo.config.{ApolloConfig, ApolloPaths, EnvChain}
import apollo.tools.*
import apollo.util.Jx
import apollo.util.Jx.*
import kyo.*

class VoiceToolsSuite extends munit.FunSuite:

  private def run[A](v: A < (Sync & Async & Scope)): A =
    import AllowUnsafe.embrace.danger
    KyoApp.Unsafe.runAndBlock(30.seconds)(Scope.run(v)).getOrThrow

  private def p(json: String) = Jx.parse(json).getOrElse(Jx.obj())

  private def ctxWith(env: Map[String, String], home: java.nio.file.Path): ToolContext < Sync =
    val paths  = ApolloPaths(home)
    val config = ApolloConfig(Absent, EnvChain(env), paths)
    AtomicRef.init(List.empty[TodoItem]).map { todo =>
      ToolContext(config = config, paths = paths, cwd = home, platform = "cli", sessionId = "v",
        approvals = new ApprovalService(config, paths, "cli", oneShot = false, yoloFlag = true),
        ui = apollo.tools.UnattendedToolUi, todo = todo,
        skills = new apollo.skills.SkillStore(config, paths))
    }

  test("pure: speechBody, sayArgs, multipartAudio, parseTranscript") {
    val b = p(VoiceTools.speechBody("tts-1", "hi", "alloy", "mp3"))
    assertEquals((b / "input").asStr, Present("hi"))
    assertEquals((b / "voice").asStr, Present("alloy"))
    assertEquals((b / "response_format").asStr, Present("mp3"))
    assertEquals(VoiceTools.sayArgs("/tmp/a.aiff", "hi"), List("say", "-o", "/tmp/a.aiff", "hi"))
    val mp = new String(VoiceTools.multipartAudio("BND", "whisper-1", "a.mp3", "AUDIO".getBytes("UTF-8")), "UTF-8")
    assert(mp.contains("--BND"), mp)
    assert(mp.contains("name=\"model\""), mp)
    assert(mp.contains("whisper-1"), mp)
    assert(mp.contains("filename=\"a.mp3\""), mp)
    assert(mp.contains("AUDIO"), mp)
    assert(mp.endsWith("--BND--\r\n"), mp)
    assertEquals(VoiceTools.parseTranscript(p("""{"text":"hello world"}""")), Result.succeed("hello world"))
    assert(VoiceTools.parseTranscript(p("""{"error":{"message":"bad"}}""")).isFailure)
  }

  test("text_to_speech (openai) saves the audio bytes and returns the path") {
    val audio = "OGGAUDIOBYTES".getBytes("UTF-8")
    val route = HttpRoute.postText("audio" / "speech").handler { _ =>
      HttpResponse.ok(new String(audio, "UTF-8")).setHeader("content-type", "audio/mpeg")
    }
    val (out, home) = run {
      Abort.run[HttpBindException](HttpServer.init(0, "127.0.0.1")(route)).map {
        case Result.Success(server) =>
          val home = java.nio.file.Files.createTempDirectory("apollo-tts")
          ctxWith(Map("OPENAI_API_KEY" -> "k", "TTS_API_BASE" -> s"http://127.0.0.1:${server.port}",
            "TTS_PROVIDER" -> "openai"), home).map { ctx =>
            ToolRegistry.dispatch("text_to_speech", """{"text":"hello","filename":"out.mp3"}""", ctx)
              .map((o, _) => (o, home))
          }
        case other => throw new AssertionError(s"bind failed: $other")
      }
    }
    assert(out.contains("saved speech"), out)
    assertEquals(java.nio.file.Files.readAllBytes(home.resolve("out.mp3")).toList, audio.toList)
  }

  test("transcribe uploads multipart audio and returns the text") {
    val captured = new java.util.concurrent.ConcurrentLinkedQueue[String]()
    val route = HttpRoute.postText("audio" / "transcriptions").handler { r =>
      captured.add(r.fields.body)
      HttpResponse.ok(Jx.render(Jx.obj("text" -> Jx.str("this is the transcript"))))
        .setHeader("content-type", "application/json")
    }
    val out = run {
      Abort.run[HttpBindException](HttpServer.init(0, "127.0.0.1")(route)).map {
        case Result.Success(server) =>
          val home = java.nio.file.Files.createTempDirectory("apollo-stt")
          java.nio.file.Files.write(home.resolve("note.mp3"), "FAKEAUDIODATA".getBytes("UTF-8"))
          ctxWith(Map("OPENAI_API_KEY" -> "k", "STT_API_BASE" -> s"http://127.0.0.1:${server.port}"), home).map { ctx =>
            ToolRegistry.dispatch("transcribe", """{"file":"note.mp3"}""", ctx).map(_._1)
          }
        case other => throw new AssertionError(s"bind failed: $other")
      }
    }
    assertEquals(out, "this is the transcript")
    import scala.jdk.CollectionConverters.*
    val body = captured.asScala.mkString
    assert(body.contains("whisper-1") && body.contains("FAKEAUDIODATA"), body)
  }
end VoiceToolsSuite

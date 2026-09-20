package apollo

import apollo.config.{ApolloConfig, ApolloPaths, EnvChain, Yaml}
import apollo.tools.*
import apollo.util.Jx
import apollo.util.Jx.*
import kyo.*

class VideoGenSuite extends munit.FunSuite:

  private def run[A](v: A < (Sync & Async & Scope)): A =
    import AllowUnsafe.embrace.danger
    KyoApp.Unsafe.runAndBlock(60.seconds)(Scope.run(v)).getOrThrow

  private def p(json: String) = Jx.parse(json).getOrElse(Jx.obj())

  test("buildBody + parseId + status classification + contentUrl") {
    val body = p(VideoGen.buildBody("sora-2", "a dog surfing", Present(8), Present("1280x720")))
    assertEquals((body / "prompt").asStr, Present("a dog surfing"))
    assertEquals((body / "model").asStr, Present("sora-2"))
    assertEquals((body / "seconds").asStr, Present("8"))
    assertEquals(VideoGen.parseId(p("""{"id":"vid_1","status":"queued"}""")), Result.succeed("vid_1"))
    assert(VideoGen.parseId(p("""{"error":{"message":"nope"}}""")).isFailure)
    assert(VideoGen.isCompleted("completed") && VideoGen.isCompleted("succeeded"))
    assert(VideoGen.isFailed("failed") && VideoGen.isTerminal("failed") && !VideoGen.isTerminal("in_progress"))
    // explicit URL wins; else the conventional content path
    assertEquals(VideoGen.contentUrl(p("""{"url":"https://cdn/v.mp4"}"""), "https://api/v1", "x"), "https://cdn/v.mp4")
    assertEquals(VideoGen.contentUrl(p("""{}"""), "https://api/v1/", "vid_1"), "https://api/v1/videos/vid_1/content")
  }

  test("video_generate submits, polls to completion, downloads the MP4, returns the path") {
    val mp4  = "MP4DATA-bytes".getBytes("UTF-8")
    val poll = new java.util.concurrent.atomic.AtomicInteger(0)

    val create = HttpRoute.postText("videos").handler { _ =>
      HttpResponse.ok(Jx.render(Jx.obj("id" -> Jx.str("vid_9"), "status" -> Jx.str("queued"))))
        .setHeader("content-type", "application/json")
    }
    val stat = HttpRoute.getText("videos" / kyo.Capture[String]("id")).handler { _ =>
      val s = if poll.getAndIncrement() == 0 then "in_progress" else "completed"
      HttpResponse.ok(Jx.render(Jx.obj("id" -> Jx.str("vid_9"), "status" -> Jx.str(s))))
        .setHeader("content-type", "application/json")
    }
    val content = HttpRoute.getText("videos" / kyo.Capture[String]("id") / "content").handler { _ =>
      HttpResponse.ok(new String(mp4, "UTF-8")).setHeader("content-type", "video/mp4")
    }

    val (out, home) = run {
      Abort.run[HttpBindException](HttpServer.init(0, "127.0.0.1")(create, stat, content)).map {
        case Result.Success(server) =>
          val home   = java.nio.file.Files.createTempDirectory("apollo-videogen")
          val paths  = ApolloPaths(home)
          val config = ApolloConfig(
            Present(Yaml.parse("video: {poll_seconds: 1, max_polls: 10}").getOrElse(throw new AssertionError("yaml"))),
            EnvChain(Map("OPENAI_API_KEY" -> "k", "VIDEO_API_BASE" -> s"http://127.0.0.1:${server.port}")), paths)
          AtomicRef.init(List.empty[TodoItem]).map { todo =>
            val ctx = ToolContext(
              config = config, paths = paths, cwd = home, platform = "cli", sessionId = "vg",
              approvals = new ApprovalService(config, paths, "cli", oneShot = false, yoloFlag = true),
              ui = apollo.tools.UnattendedToolUi, todo = todo,
              skills = new apollo.skills.SkillStore(config, paths))
            ToolRegistry.dispatch("video_generate", """{"prompt":"a dog","filename":"out.mp4"}""", ctx)
              .map((o, _) => (o, home))
          }
        case other => throw new AssertionError(s"bind failed: $other")
      }
    }
    assert(out.contains("saved video"), out)
    assertEquals(java.nio.file.Files.readAllBytes(home.resolve("out.mp4")).toList, mp4.toList)
  }

  test("video_generate errors without an API key") {
    val home   = java.nio.file.Files.createTempDirectory("apollo-videogen2")
    val paths  = ApolloPaths(home)
    val config = ApolloConfig(Absent, EnvChain(Map.empty), paths)
    val out = run {
      AtomicRef.init(List.empty[TodoItem]).map { todo =>
        val ctx = ToolContext(
          config = config, paths = paths, cwd = home, platform = "cli", sessionId = "vg",
          approvals = new ApprovalService(config, paths, "cli", oneShot = false, yoloFlag = true),
          ui = apollo.tools.UnattendedToolUi, todo = todo,
          skills = new apollo.skills.SkillStore(config, paths))
        ToolRegistry.dispatch("video_generate", """{"prompt":"x"}""", ctx).map(_._1)
      }
    }
    assert(out.contains("requires an API key"), out)
  }
end VideoGenSuite

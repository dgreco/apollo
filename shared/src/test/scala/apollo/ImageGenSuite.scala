package apollo

import apollo.config.{ApolloConfig, ApolloPaths, EnvChain}
import apollo.tools.*
import apollo.util.Jx
import kyo.*

class ImageGenSuite extends munit.FunSuite:

  private def run[A](v: A < (Sync & Async & Scope)): A =
    import AllowUnsafe.embrace.danger
    KyoApp.Unsafe.runAndBlock(30.seconds)(Scope.run(v)).getOrThrow

  private def p(json: String) = Jx.parse(json).getOrElse(Jx.obj())

  test("buildBody requests b64_json; parseResponse extracts / errors") {
    val body = Jx.parse(ImageGen.buildBody("dall-e-3", "a cat", "512x512", 1)).getOrElse(Jx.obj())
    import Jx.*
    assertEquals((body / "prompt").asStr, Present("a cat"))
    assertEquals((body / "response_format").asStr, Present("b64_json"))
    assertEquals(ImageGen.parseResponse(p("""{"data":[{"b64_json":"QUJD"}]}""")), Result.succeed("QUJD"))
    assert(ImageGen.parseResponse(p("""{"error":{"message":"bad prompt"}}""")).isFailure)
    assert(ImageGen.parseResponse(p("""{"data":[]}""")).isFailure)
  }

  test("image_generate writes the decoded PNG and returns its path") {
    val bytes = Array[Byte](-119, 80, 78, 71, 1, 2, 3) // fake PNG-ish bytes
    val b64   = java.util.Base64.getEncoder.encodeToString(bytes)
    val route = HttpRoute.postText("images" / "generations").handler { _ =>
      HttpResponse.ok(Jx.render(Jx.obj("data" -> Jx.arr(Jx.obj("b64_json" -> Jx.str(b64))))))
        .setHeader("content-type", "application/json")
    }
    val (out, home) = run {
      Abort.run[HttpBindException](HttpServer.init(0, "127.0.0.1")(route)).map {
        case Result.Success(server) =>
          val home   = java.nio.file.Files.createTempDirectory("apollo-imagegen")
          val paths  = ApolloPaths(home)
          val config = ApolloConfig(Absent, EnvChain(Map(
            "OPENAI_API_KEY" -> "k",
            "IMAGE_API_BASE" -> s"http://127.0.0.1:${server.port}"
          )), paths)
          AtomicRef.init(List.empty[TodoItem]).map { todo =>
            val ctx = ToolContext(
              config = config, paths = paths, cwd = home, platform = "cli", sessionId = "ig",
              approvals = new ApprovalService(config, paths, "cli", oneShot = false, yoloFlag = true),
              ui = apollo.cli.UnattendedToolUi, todo = todo,
              skills = new apollo.skills.SkillStore(config, paths))
            ToolRegistry.dispatch("image_generate", """{"prompt":"a cat","filename":"out.png"}""", ctx)
              .map((o, _) => (o, home))
          }
        case other => throw new AssertionError(s"bind failed: $other")
      }
    }
    assert(out.contains("saved image to"), out)
    val written = java.nio.file.Files.readAllBytes(home.resolve("out.png"))
    assertEquals(written.toList, bytes.toList)
  }

  test("image_generate errors without an API key") {
    val home   = java.nio.file.Files.createTempDirectory("apollo-imagegen2")
    val paths  = ApolloPaths(home)
    val config = ApolloConfig(Absent, EnvChain(Map.empty), paths)
    val out = run {
      AtomicRef.init(List.empty[TodoItem]).map { todo =>
        val ctx = ToolContext(
          config = config, paths = paths, cwd = home, platform = "cli", sessionId = "ig",
          approvals = new ApprovalService(config, paths, "cli", oneShot = false, yoloFlag = true),
          ui = apollo.cli.UnattendedToolUi, todo = todo,
          skills = new apollo.skills.SkillStore(config, paths))
        ToolRegistry.dispatch("image_generate", """{"prompt":"x"}""", ctx).map(_._1)
      }
    }
    assert(out.contains("requires an API key"), out)
  }
end ImageGenSuite

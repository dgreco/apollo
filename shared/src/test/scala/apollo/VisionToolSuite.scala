package apollo

import apollo.config.{ApolloConfig, ApolloPaths, EnvChain}
import apollo.tools.*
import apollo.util.Crypto
import kyo.*

/** vision_analyze tool: reads the image, base64-encodes it, invokes the injected
  * VisionRunner, and gates on the runner's presence. The runner itself (a real
  * vision-model call) is stubbed here; the wire-level image serialization is
  * already covered by the transport suites. */
class VisionToolSuite extends munit.FunSuite:

  private def run[A](v: A < (Sync & Async)): A =
    import AllowUnsafe.embrace.danger
    KyoApp.Unsafe.runAndBlock(20.seconds)(v).getOrThrow

  private val seen = new java.util.concurrent.atomic.AtomicReference[(String, String, String)](null)
  private object FakeVision extends VisionRunner:
    def analyze(mediaType: String, base64: String, prompt: String): String < (Sync & Async) =
      seen.set((mediaType, base64, prompt))
      s"SEEN($prompt)"

  private def ctx(home: java.nio.file.Path, vision: Maybe[VisionRunner]): ToolContext =
    val paths  = ApolloPaths(home)
    val config = ApolloConfig(Absent, EnvChain(Map.empty), paths)
    val todo   = run(AtomicRef.init(List.empty[TodoItem]))
    ToolContext(
      config = config, paths = paths, cwd = home, platform = "cli", sessionId = "vt",
      approvals = new ApprovalService(config, paths, "cli", oneShot = false, yoloFlag = true),
      ui = apollo.tools.UnattendedToolUi, todo = todo,
      skills = new apollo.skills.SkillStore(config, paths), vision = vision)

  private def dispatch(c: ToolContext, argsJson: String): (String, Boolean) =
    run(ToolRegistry.dispatch("vision_analyze", argsJson, c))

  test("reads image, base64-encodes, invokes runner, returns its text") {
    val home  = java.nio.file.Files.createTempDirectory("apollo-vision")
    val bytes = Array[Byte](1, 2, 3, 4, 5)
    java.nio.file.Files.write(home.resolve("pic.png"), bytes)
    seen.set(null)
    val (out, isErr) = dispatch(ctx(home, Present(FakeVision)), """{"path":"pic.png","prompt":"what is this?"}""")
    assert(!isErr, out)
    assert(out.contains("SEEN(what is this?)"), out)
    val (mt, b64, prompt) = seen.get()
    assertEquals(mt, "image/png")
    assertEquals(b64, Crypto.base64(bytes))
    assertEquals(prompt, "what is this?")
  }

  test("defaults the prompt when omitted") {
    val home = java.nio.file.Files.createTempDirectory("apollo-vision")
    java.nio.file.Files.write(home.resolve("a.jpg"), Array[Byte](9))
    seen.set(null)
    val (out, isErr) = dispatch(ctx(home, Present(FakeVision)), """{"path":"a.jpg"}""")
    assert(!isErr, out)
    assertEquals(seen.get()._1, "image/jpeg")
    assert(seen.get()._3.toLowerCase.contains("describe"), seen.get()._3)
  }

  test("unavailable without a runner, and errors on bad input") {
    val home = java.nio.file.Files.createTempDirectory("apollo-vision")
    assert(dispatch(ctx(home, Absent), """{"path":"x.png"}""")._1.contains("unavailable"))
    assert(dispatch(ctx(home, Present(FakeVision)), """{}""")._1.contains("missing required parameter"))
    assert(dispatch(ctx(home, Present(FakeVision)), """{"path":"x.txt"}""")._1.contains("unsupported image type"))
    assert(dispatch(ctx(home, Present(FakeVision)), """{"path":"nope.png"}""")._1.contains("file not found"))
  }
end VisionToolSuite

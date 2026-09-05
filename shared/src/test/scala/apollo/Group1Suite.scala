package apollo.agent

import apollo.config.{ApolloConfig, ApolloPaths, EnvChain, Yaml}
import apollo.provider.{ApiMode, ResolvedRuntime}
import apollo.skills.SkillStore
import kyo.*

/** Group-1 parity fixes: `agent.coding_instructions` injection and the
  * `auxiliary.compression` model selection.
  */
class SystemPromptCodingSuite extends munit.FunSuite:

  private def run[A](v: A < Sync): A =
    import AllowUnsafe.embrace.danger
    Sync.Unsafe.evalOrThrow(v)

  private def build(yaml: String): String =
    val paths = ApolloPaths(java.nio.file.Files.createTempDirectory("apollo-sp"))
    val cfg = ApolloConfig(
      if yaml.isEmpty then Absent else Present(Yaml.parse(yaml).getOrElse(throw new AssertionError("yaml"))),
      EnvChain(Map.empty), paths)
    run(SystemPrompt.build(SystemPrompt.Input(
      cfg, paths, new SkillStore(cfg, paths), java.nio.file.Paths.get("/tmp"),
      "cli", "m", "p", List("terminal"))))

  test("coding_instructions (list) are injected into the system prompt") {
    val out = build(
      """agent:
        |  coding_instructions:
        |    - "Always write tests first."
        |    - "Prefer immutable data."
        |""".stripMargin)
    assert(out.contains("## Coding instructions"), out)
    assert(out.contains("Always write tests first."), out)
    assert(out.contains("Prefer immutable data."), out)
  }

  test("coding_instructions accept a single string too") {
    val out = build("agent: {coding_instructions: \"Keep functions small.\"}")
    assert(out.contains("Keep functions small."), out)
  }

  test("no coding_instructions → no section") {
    assert(!build("").contains("## Coding instructions"))
  }
end SystemPromptCodingSuite

class CompressionAuxSuite extends munit.FunSuite:

  private def run[A](v: A < (Sync & Async)): A =
    import AllowUnsafe.embrace.danger
    KyoApp.Unsafe.runAndBlock(20.seconds)(v).getOrThrow

  private def main = ResolvedRuntime(
    providerSlug = "openrouter", displayName = "or", model = "main/model",
    baseUrl = "https://openrouter.ai/api/v1", apiKey = Present("k"),
    apiMode = ApiMode.ChatCompletions, headers = Map.empty, profile = Absent,
    reasoning = Absent, maxTokens = Absent, contextLength = Absent, streaming = true)

  private def cfg(yaml: String, env: Map[String, String]): ApolloConfig =
    ApolloConfig(
      if yaml.isEmpty then Absent else Present(Yaml.parse(yaml).getOrElse(throw new AssertionError("yaml"))),
      EnvChain(env), ApolloPaths(java.nio.file.Paths.get("/tmp/aux")))

  test("no auxiliary config → the main runtime is used") {
    val rt = run(Compression.auxRuntime(cfg("", Map.empty), main))
    assertEquals(rt.model, "main/model")
    assertEquals(rt.providerSlug, "openrouter")
  }

  test("auxiliary.compression selects the configured cheap model") {
    val c = cfg(
      """auxiliary:
        |  compression:
        |    provider: anthropic
        |    model: claude-haiku-4-5-20251001
        |""".stripMargin, Map("ANTHROPIC_API_KEY" -> "sk-test"))
    val rt = run(Compression.auxRuntime(c, main))
    assertEquals(rt.providerSlug, "anthropic")
    assertEquals(rt.model, "claude-haiku-4-5-20251001")
  }

  test("unresolvable auxiliary provider falls back to the main runtime") {
    // An unknown provider slug is a genuine ResolveError → fall back to main.
    val c = cfg("auxiliary: {compression: {provider: nonexistent-xyz, model: m}}", Map.empty)
    val rt = run(Compression.auxRuntime(c, main))
    assertEquals(rt.model, "main/model")
    assertEquals(rt.providerSlug, "openrouter")
  }
end CompressionAuxSuite

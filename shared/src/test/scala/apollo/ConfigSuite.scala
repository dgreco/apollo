// SPDX-FileCopyrightText: 2026 David Greco <greco@acm.org>
//
// SPDX-License-Identifier: Apache-2.0

package apollo

import apollo.config.*
import apollo.config.Yaml.*
import kyo.{Absent, Present, Result}

/** Parses the REAL upstream `cli-config.yaml.example` (2200+ lines, shipped as
  * a test fixture, refreshed from hermes-agent `main` @ 5bb314f, 2026-09-21) —
  * the config-compatibility contract. Refresh the fixture whenever upstream
  * moves: it is the only thing that proves a real config still loads.
  */
class UpstreamConfigCompatSuite extends munit.FunSuite:

  private lazy val config: ApolloConfig =
    val stream = getClass.getClassLoader.getResourceAsStream("cli-config.yaml.example")
    assert(stream != null, "fixture cli-config.yaml.example missing")
    val text = scala.io.Source.fromInputStream(stream, "UTF-8").mkString
    val node = Yaml.parse(text) match
      case Result.Success(n) => n
      case Result.Failure(e) => fail(s"real the upstream harness config example failed to parse: $e")
      case _                 => fail("parse error")
    ApolloConfig(
      Present(node),
      EnvChain(Map("MODEL_API_KEY" -> "expanded-key")),
      ApolloPaths(java.nio.file.Paths.get("/tmp/upstream-test"))
    )

  test("model section reads with the upstream harness defaults") {
    assertEquals(config.modelDefault, Present("anthropic/claude-opus-4.6"))
    assertEquals(config.modelProvider, Present("auto"))
    assertEquals(config.modelBaseUrl, Present("https://openrouter.ai/api/v1"))
    assertEquals(config.modelStreaming, true)
  }

  test("terminal / memory / compression / skills sections read") {
    assertEquals(config.terminalBackend, "local")
    assertEquals(config.terminalTimeoutSeconds, 180)
    assertEquals(config.memoryCharLimit, 2200)
    assertEquals(config.userCharLimit, 1375)
    assertEquals(config.memoryNudgeInterval, 10)
    assertEquals(config.compressionThreshold, 0.5)
    assertEquals(config.protectLastN, 20)
    assertEquals(config.minTailUserMessages, 1)
    assertEquals(config.skillCreationNudgeInterval, 15)
  }

  test("agent section: reasoning effort and personalities") {
    assertEquals(config.reasoningEffort, "medium")
    assertEquals(config.verbose, false)
    assertEquals(config.maxTurns, Present(500))
  }

  test("platform_toolsets are read per platform (upstream legacy names verbatim)") {
    assertEquals(config.platformToolsets("cli"), Present(List("hermes-cli")))
    assertEquals(config.platformToolsets("telegram"), Present(List("hermes-telegram")))
    assertEquals(config.platformToolsets("nonexistent"), Absent)
  }

  test("post-0.21 keys read with the upstream defaults") {
    // Absolute compression cap, on by default upstream since 0.21.x.
    assertEquals(config.compressionThresholdTokens, Present(256000L))
    // display.show_reasoning flipped to true.
    assertEquals(config.showReasoning, true)
    // The instruction-file write gate is on unless turned off.
    assertEquals(config.protectedInstructionFiles, true)
    assertEquals(config.protectedInstructionExtraPatterns, Nil)
  }

  test("sections added upstream after this build still navigate (parse-and-ignore)") {
    // `auth:` (login policy) and `cron.catch_up_missed` are not acted on here,
    // but must not break the load.
    assert(config.root.flatMap(_.path("auth", "adopt_external_logins")).flatMap(_.bool) == Present(true))
    assert(config.root.flatMap(_.path("cron", "catch_up_missed")).flatMap(_.bool) == Present(true))
    assert(config.root.flatMap(_.path("updates", "check")).flatMap(_.bool) == Present(true))
  }

  test("display and session settings read") {
    assertEquals(config.displayCompact, false)
    assertEquals(config.toolProgress, "all")
    assertEquals(config.groupSessionsPerUser, true)
    assertEquals(config.gatewayStreamingEnabled, false)
  }

  test("unknown keys are preserved-and-ignored (whole file loads)") {
    // Sections this build doesn't act on still navigate without error.
    assert(config.root.flatMap(_.path("stt", "enabled")).flatMap(_.bool) == Present(true))
    assert(config.root.flatMap(_.path("kanban", "review_dispatch")).flatMap(_.bool) == Present(true))
  }

class EnvChainSuite extends munit.FunSuite:

  test(".env overrides the process environment (upstream override=True)") {
    val chain = EnvChain.layered(
      process = Map("KEY" -> "from-process", "ONLY_PROC" -> "p"),
      userEnv = Present(Map("KEY" -> "from-dotenv", "ONLY_ENVFILE" -> "d")),
      opEnv = Absent,
      managedEnv = Absent
    )
    assertEquals(chain.get("KEY"), Present("from-dotenv"))
    assertEquals(chain.get("ONLY_PROC"), Present("p"))
    assertEquals(chain.get("ONLY_ENVFILE"), Present("d"))
  }

  test(".op.env gap-fills only, and is skipped when the OP token is already set") {
    val gapFill = EnvChain.layered(
      process = Map("EXISTING" -> "shell"),
      userEnv = Present(Map("FROM_ENV" -> "env")),
      opEnv = Present(Map("EXISTING" -> "op", "OP_ONLY" -> "op", "OP_SERVICE_ACCOUNT_TOKEN" -> "tok")),
      managedEnv = Absent
    )
    assertEquals(gapFill.get("EXISTING"), Present("shell")) // override=False
    assertEquals(gapFill.get("OP_ONLY"), Present("op"))

    val skipped = EnvChain.layered(
      process = Map("OP_SERVICE_ACCOUNT_TOKEN" -> "already"),
      userEnv = Absent,
      opEnv = Present(Map("OP_ONLY" -> "op")),
      managedEnv = Absent
    )
    assertEquals(skipped.get("OP_ONLY"), Absent) // whole file skipped
  }

  test("managed .env is applied last and beats user and shell") {
    val chain = EnvChain.layered(
      process = Map("K" -> "shell"),
      userEnv = Present(Map("K" -> "user")),
      opEnv = Absent,
      managedEnv = Present(Map("K" -> "managed"))
    )
    assertEquals(chain.get("K"), Present("managed"))
  }

  test("empty values are treated as unset") {
    val chain = EnvChain.layered(Map("A" -> "real"), Present(Map("A" -> "  ")), Absent, Absent)
    assertEquals(chain.get("A"), Absent) // .env overrode with blank → unset
    assertEquals(EnvChain(Map("B" -> "  ")).get("B"), Absent)
  }

class VarExpansionSuite extends munit.FunSuite:

  test("expands ${VAR} and ${env:VAR}; keeps unresolvable refs verbatim") {
    val yaml =
      """model:
        |  api_key: "${MY_KEY}"
        |  base_url: "${env:MY_URL}/v1"
        |  default: "${UNSET_VAR}"
        |""".stripMargin
    val node = Yaml.parse(yaml).getOrElse(fail("parse"))
    val cfg = ApolloConfig(
      Present(node),
      EnvChain(Map("MY_KEY" -> "k-123", "MY_URL" -> "https://x.example")),
      ApolloPaths(java.nio.file.Paths.get("/tmp"))
    )
    assertEquals(cfg.modelApiKey, Present("k-123"))
    assertEquals(cfg.modelBaseUrl, Present("https://x.example/v1"))
    assertEquals(cfg.modelDefault, Present("${UNSET_VAR}"))
  }

class RootModelKeySuite extends munit.FunSuite:

  private def cfg(yaml: String): ApolloConfig =
    ApolloConfig(
      Present(Yaml.parse(yaml).getOrElse(fail("parse"))),
      EnvChain(Map.empty),
      ApolloPaths(java.nio.file.Paths.get("/tmp"))
    )

  test("root provider/base_url/context_length hoist under model when model.* is empty") {
    val c = cfg(
      """provider: "zai"
        |base_url: "https://api.z.ai/api/paas/v4"
        |context_length: 131072
        |model: "glm-5.2"
        |""".stripMargin
    )
    assertEquals(c.modelProvider, Present("zai"))
    assertEquals(c.modelBaseUrl, Present("https://api.z.ai/api/paas/v4"))
    assertEquals(c.contextLength, Present(131072))
    assertEquals(c.modelDefault, Present("glm-5.2"))
  }

  test("root keys never override explicit model.* keys") {
    val c = cfg(
      """provider: "root-provider"
        |model:
        |  provider: "model-provider"
        |  default: "m1"
        |""".stripMargin
    )
    assertEquals(c.modelProvider, Present("model-provider"))
  }

  test("api_base aliases base_url (issue #8919), fallback-only") {
    assertEquals(cfg("model:\n  api_base: \"https://a.example/v1\"\n").modelBaseUrl,
      Present("https://a.example/v1"))
    val both = cfg("model:\n  base_url: \"https://real\"\n  api_base: \"https://alias\"\n")
    assertEquals(both.modelBaseUrl, Present("https://real"))
  }

  test("model id: default > model > name; dict values flattened (issue #34500)") {
    assertEquals(cfg("model:\n  name: \"via-name\"\n").modelDefault, Present("via-name"))
    assertEquals(cfg("model:\n  model: \"via-model\"\n  name: \"via-name\"\n").modelDefault,
      Present("via-model"))
    assertEquals(
      cfg("model:\n  default:\n    model: \"nested\"\n    provider: \"p\"\n").modelDefault,
      Present("nested")
    )
  }

class CompressionCapSuite extends munit.FunSuite:

  private def cfg(yaml: String): ApolloConfig =
    ApolloConfig(
      Present(Yaml.parse(yaml).getOrElse(fail("parse"))),
      EnvChain(Map.empty),
      ApolloPaths(java.nio.file.Paths.get("/tmp"))
    )

  test("threshold_tokens: unset = upstream default, explicit null = ratio-only") {
    assertEquals(cfg("compression:\n  threshold: 0.5\n").compressionThresholdTokens, Present(256000L))
    assertEquals(cfg("compression:\n  threshold_tokens: null\n").compressionThresholdTokens, Absent)
    assertEquals(cfg("compression:\n  threshold_tokens: 120000\n").compressionThresholdTokens,
      Present(120000L))
    // A nonsensical cap is ignored rather than compressing on every turn.
    assertEquals(cfg("compression:\n  threshold_tokens: 0\n").compressionThresholdTokens, Absent)
  }

class ManagedOverlaySuite extends munit.FunSuite:

  test("managed config deep-merges over user config, winning at the leaf") {
    val user    = Yaml.parse(
      """model:
        |  provider: "openrouter"
        |  default: "user-model"
        |agent:
        |  verbose: true
        |""".stripMargin).getOrElse(fail("user"))
    val managed = Yaml.parse(
      """model:
        |  provider: "anthropic"
        |approvals:
        |  mode: "smart"
        |""".stripMargin).getOrElse(fail("managed"))
    val merged = Yaml.deepMerge(user, managed)
    val c = ApolloConfig(Present(merged), EnvChain(Map.empty),
      ApolloPaths(java.nio.file.Paths.get("/tmp")))
    assertEquals(c.modelProvider, Present("anthropic"))   // managed leaf wins
    assertEquals(c.modelDefault, Present("user-model"))   // user leaf survives
    assertEquals(c.verbose, true)                         // untouched section survives
    assertEquals(c.approvalMode, "smart")                 // managed-only section added
  }

class SpliceModelBlockSuite extends munit.FunSuite:
  import apollo.cli.SetupWizard

  private val block = "model:\n  provider: \"zai\"\n  default: \"glm-5.2\"\n"

  test("no existing file creates a minimal config") {
    val out = SetupWizard.spliceModelBlock(Absent, block)
    assert(out.contains("provider: \"zai\""))
    assert(out.startsWith("#"))
  }

  test("no model block appends one, preserving the file") {
    val out = SetupWizard.spliceModelBlock(Present("agent:\n  verbose: true\n"), block)
    assert(out.contains("verbose: true"))
    assert(out.contains("provider: \"zai\""))
  }

  test("existing block is replaced through its indented lines only") {
    val existing =
      """# header comment
        |model:
        |  provider: "openrouter"
        |  default: "old-model"
        |
        |  base_url: "https://old"
        |# ===== next section banner =====
        |agent:
        |  verbose: true
        |""".stripMargin
    val out = SetupWizard.spliceModelBlock(Present(existing), block)
    assert(out.contains("# header comment"))
    assert(out.contains("# ===== next section banner ====="))
    assert(out.contains("verbose: true"))
    assert(out.contains("glm-5.2"))
    assert(!out.contains("old-model"))
    assert(!out.contains("https://old"))
  }

  test("block at end of file is replaced cleanly") {
    val out = SetupWizard.spliceModelBlock(Present("agent:\n  verbose: true\nmodel:\n  default: \"x\"\n"), block)
    assert(out.contains("verbose: true"))
    assert(out.contains("glm-5.2"))
    assert(!out.contains("\"x\""))
  }

  test("the result still parses and reads back the new values") {
    val out  = SetupWizard.spliceModelBlock(
      Present("model:\n  provider: \"openrouter\"\nagent:\n  verbose: true\n"), block)
    val node = Yaml.parse(out).getOrElse(fail(s"unparseable splice result:\n$out"))
    val cfg = ApolloConfig(Present(node), EnvChain(Map.empty),
      ApolloPaths(java.nio.file.Paths.get("/tmp")))
    assertEquals(cfg.modelProvider, Present("zai"))
    assertEquals(cfg.modelDefault, Present("glm-5.2"))
    assertEquals(cfg.verbose, true)
  }

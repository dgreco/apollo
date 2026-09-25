// SPDX-FileCopyrightText: 2026 David Greco <greco@acm.org>
//
// SPDX-License-Identifier: Apache-2.0

package apollo.provider

import apollo.config.{ApolloConfig, ApolloPaths, EnvChain, Yaml}
import kyo.*

/** `model.key_cmd`: minting a (short-lived) credential from a command for any
  * profile — the mechanism behind Vertex (gcloud) and Azure Entra (az). */
class KeyCmdSuite extends munit.FunSuite:

  private def run[A](v: A < (Sync & Async)): A =
    import AllowUnsafe.embrace.danger
    KyoApp.Unsafe.runAndBlock(20.seconds)(v).getOrThrow

  private def cfg(yaml: String, env: Map[String, String] = Map.empty): ApolloConfig =
    ApolloConfig(Present(Yaml.parse(yaml).getOrElse(throw new AssertionError("yaml"))),
      EnvChain(env), ApolloPaths(java.nio.file.Paths.get("/tmp/keycmd-test")))

  private def resolve(c: ApolloConfig, ov: RuntimeOverrides = RuntimeOverrides()): Result[ResolveError, ResolvedRuntime] =
    run(Abort.run[ResolveError](Runtime.resolve(c, ov)))

  test("model.key_cmd supplies the api key (bare token)") {
    val r = resolve(cfg(
      """model:
        |  provider: openai-api
        |  default: gpt-4o
        |  key_cmd: "echo sk-FROMCMD"
        |""".stripMargin))
    assert(r.isSuccess, r.toString)
    assertEquals(r.getOrElse(null).apiKey, Present("sk-FROMCMD"))
  }

  test("model.key_cmd parses a JSON access_token") {
    val r = resolve(cfg(
      """model:
        |  provider: openai-api
        |  default: gpt-4o
        |  key_cmd: "echo '{\"access_token\":\"sk-json\"}'"
        |""".stripMargin))
    assert(r.isSuccess, r.toString)
    assertEquals(r.getOrElse(null).apiKey, Present("sk-json"))
  }

  test("--api-key overrides key_cmd; key_cmd overrides env keys") {
    val c = cfg(
      """model:
        |  provider: openai-api
        |  default: gpt-4o
        |  key_cmd: "echo sk-CMD"
        |""".stripMargin, env = Map("OPENAI_API_KEY" -> "sk-ENV"))
    assertEquals(resolve(c).getOrElse(null).apiKey, Present("sk-CMD"))
    assertEquals(resolve(c, RuntimeOverrides(apiKey = Present("sk-OVERRIDE"))).getOrElse(null).apiKey,
      Present("sk-OVERRIDE"))
  }

  test("a failing key_cmd surfaces KeyCommandFailed") {
    val r = resolve(cfg(
      """model:
        |  provider: openai-api
        |  default: gpt-4o
        |  key_cmd: "false"
        |""".stripMargin))
    assert(r.isFailure, r.toString)
  }

  test("vertex is now resolvable via base_url + key_cmd (no longer unsupported)") {
    val r = resolve(cfg(
      """model:
        |  provider: vertex
        |  base_url: "https://us-central1-aiplatform.googleapis.com/v1/projects/p/locations/us-central1/endpoints/openapi"
        |  default: google/gemini-2.5-pro
        |  key_cmd: "echo ya29.TOKEN"
        |""".stripMargin))
    assert(r.isSuccess, r.toString)
    val rt = r.getOrElse(null)
    assertEquals(rt.apiKey, Present("ya29.TOKEN"))
    assertEquals(rt.apiMode, ApiMode.ChatCompletions)
    assert(rt.baseUrl.contains("aiplatform.googleapis.com"), rt.baseUrl)
  }
end KeyCmdSuite

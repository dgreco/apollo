// SPDX-FileCopyrightText: 2026 David Greco <greco@acm.org>
//
// SPDX-License-Identifier: Apache-2.0

package apollo.config

import apollo.util.Jx
import kyo.*

class SecretsSuite extends munit.FunSuite:

  private def run[A](v: A < (Sync & Async)): A =
    import AllowUnsafe.embrace.danger
    KyoApp.Unsafe.runAndBlock(20.seconds)(v).getOrThrow

  private def cfg(yaml: String, env: Map[String, String] = Map.empty): ApolloConfig =
    ApolloConfig(
      if yaml.isEmpty then Absent else Present(Yaml.parse(yaml).getOrElse(throw new AssertionError("yaml"))),
      EnvChain(env), ApolloPaths(java.nio.file.Paths.get("/tmp/secrets-test")))

  test("pure: op/bws arg builders + bws JSON parse") {
    assertEquals(Secrets.opReadArgs("op", "op://Vault/Item/field"), List("op", "read", "--", "op://Vault/Item/field"))
    assertEquals(Secrets.bwsListArgs("bws", "proj1"), List("bws", "secret", "list", "proj1"))
    val j = Jx.parse("""[{"id":"1","key":"API_KEY","value":"sk-a"},{"key":"DB","value":"pw"}]""").getOrElse(Jx.arr())
    assertEquals(Secrets.parseBwsSecrets(j), List("API_KEY" -> "sk-a", "DB" -> "pw"))
  }

  test("merge: first-claim-wins, gap-fill vs override") {
    val existing = Map("A" -> "envA")
    // gap-fill: A already in env → skipped; B added; duplicate C → first wins
    assertEquals(
      Secrets.merge(existing, List("A" -> "x", "B" -> "b", "C" -> "c1", "C" -> "c2"), override0 = false),
      Map("B" -> "b", "C" -> "c1"))
    // override: A now included
    assertEquals(
      Secrets.merge(existing, List("A" -> "x"), override0 = true),
      Map("A" -> "x"))
  }

  test("applyTo resolves a command source into the env (gap-fill)") {
    val c = cfg(
      """secrets:
        |  sources: [command]
        |  command:
        |    env:
        |      MYSECRET: "echo sk-FROMCMD"
        |      PRESET: "echo should-not-win"
        |""".stripMargin, env = Map("PRESET" -> "already"))
    val updated = run(Secrets.applyTo(c))
    assertEquals(updated.env.get("MYSECRET"), Present("sk-FROMCMD"))
    assertEquals(updated.env.get("PRESET"), Present("already")) // gap-fill: existing env preserved
  }

  test("applyTo with override_existing lets a resolved value win") {
    val c = cfg(
      """secrets:
        |  sources: [command]
        |  override_existing: true
        |  command: {env: {PRESET: "echo overridden"}}
        |""".stripMargin, env = Map("PRESET" -> "already"))
    assertEquals(run(Secrets.applyTo(c)).env.get("PRESET"), Present("overridden"))
  }

  test("no sources → config unchanged; fail-open on a bad command") {
    val none = cfg("")
    assertEquals(run(Secrets.applyTo(none)).env.resolved, none.env.resolved)
    val bad = cfg("secrets: {sources: [command], command: {env: {X: \"false\"}}}")
    assertEquals(run(Secrets.applyTo(bad)).env.get("X"), Absent) // failed command → not set, no crash
  }
end SecretsSuite

// SPDX-FileCopyrightText: 2026 David Greco <greco@acm.org>
//
// SPDX-License-Identifier: Apache-2.0

package apollo.provider

import apollo.config.ApolloPaths
import apollo.util.Jx
import kyo.*

/** Pure Copilot auth parsing + poll classification + token storage. (The live
  * device-code / token-exchange HTTP is validated with `apollo auth copilot
  * login`, not in CI.) */
class CopilotAuthSuite extends munit.FunSuite:

  private def run[A](v: A < (Sync & Async)): A =
    import AllowUnsafe.embrace.danger
    KyoApp.Unsafe.runAndBlock(20.seconds)(v).getOrThrow

  private def p(json: String) = Jx.parse(json).getOrElse(Jx.obj())

  test("parseExchange reads token + expiry, fails cleanly") {
    assertEquals(CopilotAuth.parseExchange(p("""{"token":"tid=abc;ol=1","expires_at":1700000000}""")),
      Result.succeed(CopilotAuth.Token("tid=abc;ol=1", 1700000000L)))
    assert(CopilotAuth.parseExchange(p("""{"message":"Bad credentials"}""")).isFailure)
  }

  test("parseDeviceCode reads the fields with defaults") {
    val d = CopilotAuth.parseDeviceCode(p(
      """{"device_code":"dc","user_code":"WXYZ-1234","verification_uri":"https://github.com/login/device","interval":7,"expires_in":600}"""))
    assertEquals(d, Result.succeed(CopilotAuth.DeviceCode("dc", "WXYZ-1234", "https://github.com/login/device", 7, 600)))
    assert(CopilotAuth.parseDeviceCode(p("""{"user_code":"x"}""")).isFailure)
  }

  test("classifyPoll maps success / pending / slow_down / error") {
    assertEquals(CopilotAuth.classifyPoll(p("""{"access_token":"gho_x"}""")), CopilotAuth.Poll.Success("gho_x"))
    assertEquals(CopilotAuth.classifyPoll(p("""{"error":"authorization_pending"}""")), CopilotAuth.Poll.Pending)
    assertEquals(CopilotAuth.classifyPoll(p("""{"error":"slow_down"}""")), CopilotAuth.Poll.SlowDown)
    assertEquals(CopilotAuth.classifyPoll(p("""{"error":"expired_token"}""")), CopilotAuth.Poll.Error("expired_token"))
    assertEquals(CopilotAuth.classifyPoll(p("""{}""")), CopilotAuth.Poll.Error("unknown poll error"))
  }

  test("github token storage round-trips and logout clears it") {
    val paths = ApolloPaths(java.nio.file.Files.createTempDirectory("apollo-copilot"))
    assertEquals(run(CopilotAuth.loadGithubToken(paths)), Absent)
    run(CopilotAuth.saveGithubToken(paths, "gho_secret"))
    assertEquals(run(CopilotAuth.loadGithubToken(paths)), Present("gho_secret"))
    run(CopilotAuth.logout(paths))
    assertEquals(run(CopilotAuth.loadGithubToken(paths)), Absent)
  }

  test("copilot headers include the Copilot integration id") {
    assertEquals(CopilotAuth.headers.get("Copilot-Integration-Id"), Some("vscode-chat"))
  }
end CopilotAuthSuite

// SPDX-FileCopyrightText: 2026 David Greco <greco@acm.org>
//
// SPDX-License-Identifier: Apache-2.0

package apollo.provider

import apollo.config.ApolloPaths
import apollo.util.Jx
import kyo.*
import kyo.Structure.Value

/** Qwen device-flow OAuth: pure parsers, poll classification, resource-URL
  * normalization, and the token-storage round-trip. */
class QwenAuthSuite extends munit.FunSuite:

  private def run[A](v: A < (Sync & Async)): A =
    import AllowUnsafe.embrace.danger
    KyoApp.Unsafe.runAndBlock(20.seconds)(v).getOrThrow

  private def p(json: String): Value = Jx.parse(json).getOrElse(Jx.obj())

  test("parseDeviceCode extracts fields and threads the verifier") {
    val dc = QwenAuth.parseDeviceCode(p(
      """{"device_code":"DC","user_code":"WXYZ","verification_uri":"https://qwen/act",
         "verification_uri_complete":"https://qwen/act?code=WXYZ","interval":5,"expires_in":300}"""), "VER")
    assert(dc.isSuccess, dc.toString)
    val d = dc.getOrElse(null)
    assertEquals(d.deviceCode, "DC")
    assertEquals(d.userCode, "WXYZ")
    assertEquals(d.verificationUriComplete, Present("https://qwen/act?code=WXYZ"))
    assertEquals(d.verifier, "VER")
    assert(QwenAuth.parseDeviceCode(p("""{"user_code":"x"}"""), "v").isFailure)
  }

  test("parseTokenBundle computes expiry and captures refresh + resource_url") {
    val t = QwenAuth.parseTokenBundle(p(
      """{"access_token":"AT","refresh_token":"RT","expires_in":3600,"resource_url":"portal.qwen.ai/v1"}"""),
      nowMillis = 1_000_000L)
    assert(t.isSuccess, t.toString)
    val tok = t.getOrElse(null)
    assertEquals(tok.accessToken, "AT")
    assertEquals(tok.refreshToken, Present("RT"))
    assertEquals(tok.resourceUrl, Present("portal.qwen.ai/v1"))
    assertEquals(tok.expiresAt, 1_000_000L + 3_600_000L)
    assert(QwenAuth.parseTokenBundle(p("""{"error":"invalid_grant","error_description":"nope"}"""), 0L).isFailure)
  }

  test("classifyPoll maps pending / slow_down / success / error") {
    assertEquals(QwenAuth.classifyPoll(p("""{"error":"authorization_pending"}"""), 0L), QwenAuth.Poll.Pending)
    assertEquals(QwenAuth.classifyPoll(p("""{"error":"slow_down"}"""), 0L), QwenAuth.Poll.SlowDown)
    QwenAuth.classifyPoll(p("""{"access_token":"AT","expires_in":10}"""), 5L) match
      case QwenAuth.Poll.Success(t) => assertEquals(t.accessToken, "AT"); assertEquals(t.expiresAt, 5L + 10_000L)
      case other                    => fail(s"expected success, got $other")
    QwenAuth.classifyPoll(p("""{"error":"expired_token","error_description":"gone"}"""), 0L) match
      case QwenAuth.Poll.Error(m) => assertEquals(m, "gone")
      case other                  => fail(s"expected error, got $other")
  }

  test("normalizeApiBase / apiBase") {
    assertEquals(QwenAuth.normalizeApiBase("portal.qwen.ai"), "https://portal.qwen.ai/v1")
    assertEquals(QwenAuth.normalizeApiBase("https://dashscope.aliyuncs.com/compatible-mode/v1"),
      "https://dashscope.aliyuncs.com/compatible-mode/v1")
    assertEquals(QwenAuth.normalizeApiBase("https://x.qwen.ai/"), "https://x.qwen.ai/v1")
    // no resource_url → fallback
    assertEquals(QwenAuth.apiBase(QwenAuth.Token("AT", Absent, 0L, Absent)), QwenAuth.fallbackApiBase)
    assertEquals(QwenAuth.apiBase(QwenAuth.Token("AT", Absent, 0L, Present("portal.qwen.ai"))),
      "https://portal.qwen.ai/v1")
  }

  test("isExpired honors the 60s margin and unknown expiry") {
    assert(QwenAuth.isExpired(QwenAuth.Token("a", Absent, 1_000L, Absent), nowMillis = 2_000L))
    assert(!QwenAuth.isExpired(QwenAuth.Token("a", Absent, 10_000_000L, Absent), nowMillis = 1_000L))
    assert(!QwenAuth.isExpired(QwenAuth.Token("a", Absent, 0L, Absent), nowMillis = 9_999_999L)) // unknown
  }

  test("save + load round-trips a token bundle") {
    val home  = java.nio.file.Files.createTempDirectory("apollo-qwen")
    val paths = ApolloPaths(home)
    val tok   = QwenAuth.Token("AT-123", Present("RT-456"), 42L, Present("portal.qwen.ai/v1"))
    val back  = run(QwenAuth.save(paths, tok).andThen(QwenAuth.load(paths)))
    assertEquals(back, Present(tok))
    assertEquals(run(QwenAuth.logout(paths).andThen(QwenAuth.load(paths))), Absent)
  }
end QwenAuthSuite

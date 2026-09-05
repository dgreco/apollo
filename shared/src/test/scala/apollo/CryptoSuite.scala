package apollo.util

class CryptoSuite extends munit.FunSuite:

  private def hex(b: Array[Byte]): String = b.map(x => f"${x & 0xff}%02x").mkString

  test("SHA-256 matches FIPS 180-4 / NIST vectors") {
    assertEquals(hex(Crypto.sha256("".getBytes("UTF-8"))),
      "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
    assertEquals(hex(Crypto.sha256("abc".getBytes("UTF-8"))),
      "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
    assertEquals(hex(Crypto.sha256("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".getBytes("UTF-8"))),
      "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1")
    // A message spanning multiple 64-byte blocks (896 bits → padding into a new block).
    val million = Array.fill(1000)("a").mkString
    assertEquals(hex(Crypto.sha256(million.getBytes("UTF-8"))),
      "41edece42d63e8d9bf515a9ba6932e1c20cbc9f5a5d134645adb5db1b9737ea3")
  }

  // (The cross-check against the JVM's own MessageDigest lives in the
  // JVM-only CryptoJvmSuite — java.security.MessageDigest is absent on Native.)

  test("base64url has no padding and is URL-safe") {
    val out = Crypto.base64Url(Array[Byte](-1, -2, -3, 0, 65))
    assert(!out.contains("="), out)
    assert(!out.contains("+") && !out.contains("/"), out)
  }

  test("PKCE challenge matches the RFC 7636 appendix B example") {
    // verifier "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk" →
    // challenge "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
    assertEquals(
      Crypto.pkceChallenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
      "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM")
  }

  test("HMAC-SHA256 matches RFC 4231 test vectors") {
    // Case 1: key = 0x0b×20, data = "Hi There".
    val k1 = Array.fill[Byte](20)(0x0b)
    assertEquals(hex(Crypto.hmacSha256(k1, "Hi There")),
      "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7")
    // Case 2: key = "Jefe", data = "what do ya want for nothing?".
    assertEquals(hex(Crypto.hmacSha256("Jefe".getBytes("UTF-8"), "what do ya want for nothing?")),
      "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843")
  }

  test("AWS SigV4 signing-key derivation matches the AWS documented vector") {
    // From the AWS docs "Examples of how to derive a signing key".
    val key = apollo.provider.SigV4.deriveKey(
      "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY", "20120215", "us-east-1", "iam")
    assertEquals(hex(key), "f4780e2d9f65fa895f9c67b32ce1baf0b0d8a43505a000a1a9e090d414db404d")
  }

  test("randomToken yields distinct, sufficiently long URL-safe tokens") {
    val a = Crypto.randomToken(32)
    val b = Crypto.randomToken(32)
    assertNotEquals(a, b)
    assert(a.length >= 43, a) // 32 bytes base64url ≈ 43 chars
    assert(!a.contains("=") && !a.contains("+") && !a.contains("/"), a)
  }
end CryptoSuite

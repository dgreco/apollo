package apollo.util

/** JVM-only: cross-validate the portable SHA-256 against the platform's own
  * `java.security.MessageDigest` (which Scala Native's javalib lacks, hence
  * the split from the shared `CryptoSuite`).
  */
class CryptoJvmSuite extends munit.FunSuite:

  private def hex(b: Array[Byte]): String = b.map(x => f"${x & 0xff}%02x").mkString

  test("SHA-256 agrees with the JVM's MessageDigest across random inputs") {
    val md  = java.security.MessageDigest.getInstance("SHA-256")
    val rnd = new scala.util.Random(1234)
    for _ <- 1 to 200 do
      val bytes = new Array[Byte](rnd.nextInt(200))
      rnd.nextBytes(bytes)
      assertEquals(hex(Crypto.sha256(bytes)), hex(md.digest(bytes)))
  }
end CryptoJvmSuite

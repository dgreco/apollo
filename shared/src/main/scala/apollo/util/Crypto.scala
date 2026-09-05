package apollo.util

/** Small, dependency-free crypto primitives that behave identically on the
  * JVM and Scala Native. `java.security.MessageDigest` is not a safe bet on
  * Native, so SHA-256 is implemented here (FIPS 180-4); it is only used for
  * the PKCE code challenge, never for anything that must resist a determined
  * attacker beyond what the OAuth flow itself assumes.
  */
object Crypto:

  /** URL-safe base64 without padding (RFC 4648 §5 / RFC 7636 base64url). */
  def base64Url(bytes: Array[Byte]): String =
    java.util.Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)

  /** Standard base64 with padding (RFC 4648 §4) — for data: URLs / image blocks. */
  def base64(bytes: Array[Byte]): String =
    java.util.Base64.getEncoder.encodeToString(bytes)

  /** A high-entropy URL-safe token of at least `nBytes` bytes of randomness,
    * drawn from `UUID.randomUUID` (CSPRNG-backed and portable — Native's
    * `SecureRandom` support is uneven). Used for the PKCE `code_verifier`
    * and the `state` parameter.
    */
  def randomToken(nBytes: Int): String =
    val buf = new java.io.ByteArrayOutputStream()
    while buf.size() < nBytes do
      val u = java.util.UUID.randomUUID
      val bb = java.nio.ByteBuffer.allocate(16)
      bb.putLong(u.getMostSignificantBits)
      bb.putLong(u.getLeastSignificantBits)
      buf.write(bb.array())
    base64Url(buf.toByteArray.take(nBytes))

  // --- SHA-256 (FIPS 180-4) ------------------------------------------------

  private val K: Array[Int] = Array(
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
    0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
    0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
    0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2
  )

  def sha256(message: Array[Byte]): Array[Byte] =
    var h0 = 0x6a09e667; var h1 = 0xbb67ae85; var h2 = 0x3c6ef372; var h3 = 0xa54ff53a
    var h4 = 0x510e527f; var h5 = 0x9b05688c; var h6 = 0x1f83d9ab; var h7 = 0x5be0cd19

    // Pad: append 0x80, then zeros, then the 64-bit bit-length.
    val ml     = message.length.toLong * 8
    val padLen = ((56 - (message.length + 1) % 64) + 64) % 64
    val padded = new Array[Byte](message.length + 1 + padLen + 8)
    System.arraycopy(message, 0, padded, 0, message.length)
    padded(message.length) = 0x80.toByte
    var i = 0
    while i < 8 do
      padded(padded.length - 1 - i) = ((ml >>> (8 * i)) & 0xff).toByte
      i += 1

    def rotr(x: Int, n: Int): Int = (x >>> n) | (x << (32 - n))

    val w = new Array[Int](64)
    var chunk = 0
    while chunk < padded.length do
      var t = 0
      while t < 16 do
        val j = chunk + t * 4
        w(t) = ((padded(j) & 0xff) << 24) | ((padded(j + 1) & 0xff) << 16) |
          ((padded(j + 2) & 0xff) << 8) | (padded(j + 3) & 0xff)
        t += 1
      while t < 64 do
        val s0 = rotr(w(t - 15), 7) ^ rotr(w(t - 15), 18) ^ (w(t - 15) >>> 3)
        val s1 = rotr(w(t - 2), 17) ^ rotr(w(t - 2), 19) ^ (w(t - 2) >>> 10)
        w(t) = w(t - 16) + s0 + w(t - 7) + s1
        t += 1

      var a = h0; var b = h1; var c = h2; var d = h3
      var e = h4; var f = h5; var g = h6; var hh = h7
      t = 0
      while t < 64 do
        val bigS1 = rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25)
        val ch    = (e & f) ^ (~e & g)
        val temp1 = hh + bigS1 + ch + K(t) + w(t)
        val bigS0 = rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22)
        val maj   = (a & b) ^ (a & c) ^ (b & c)
        val temp2 = bigS0 + maj
        hh = g; g = f; f = e; e = d + temp1
        d = c; c = b; b = a; a = temp1 + temp2
        t += 1

      h0 += a; h1 += b; h2 += c; h3 += d; h4 += e; h5 += f; h6 += g; h7 += hh
      chunk += 64

    val out = new Array[Byte](32)
    val hs  = Array(h0, h1, h2, h3, h4, h5, h6, h7)
    var k = 0
    while k < 8 do
      out(k * 4)     = ((hs(k) >>> 24) & 0xff).toByte
      out(k * 4 + 1) = ((hs(k) >>> 16) & 0xff).toByte
      out(k * 4 + 2) = ((hs(k) >>> 8) & 0xff).toByte
      out(k * 4 + 3) = (hs(k) & 0xff).toByte
      k += 1
    out
  end sha256

  /** The PKCE S256 challenge: base64url(SHA-256(code_verifier)). */
  def pkceChallenge(verifier: String): String =
    base64Url(sha256(verifier.getBytes(java.nio.charset.StandardCharsets.US_ASCII)))

  // --- hex + HMAC-SHA256 (for AWS SigV4) ----------------------------------

  def hex(bytes: Array[Byte]): String =
    val sb = new StringBuilder(bytes.length * 2)
    var i = 0
    while i < bytes.length do
      sb.append(f"${bytes(i) & 0xff}%02x")
      i += 1
    sb.toString

  def sha256Hex(s: String): String =
    hex(sha256(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)))

  /** HMAC-SHA256 (RFC 2104) built on the portable SHA-256 above. */
  def hmacSha256(key: Array[Byte], message: Array[Byte]): Array[Byte] =
    val blockSize = 64
    val k0 =
      if key.length > blockSize then
        val h = sha256(key); h ++ new Array[Byte](blockSize - h.length)
      else key ++ new Array[Byte](blockSize - key.length)
    val ipad = new Array[Byte](blockSize)
    val opad = new Array[Byte](blockSize)
    var i = 0
    while i < blockSize do
      ipad(i) = (k0(i) ^ 0x36).toByte
      opad(i) = (k0(i) ^ 0x5c).toByte
      i += 1
    sha256(opad ++ sha256(ipad ++ message))

  def hmacSha256(key: Array[Byte], message: String): Array[Byte] =
    hmacSha256(key, message.getBytes(java.nio.charset.StandardCharsets.UTF_8))
end Crypto

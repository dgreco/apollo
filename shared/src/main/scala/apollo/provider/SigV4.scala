// SPDX-FileCopyrightText: 2026 David Greco <greco@acm.org>
//
// SPDX-License-Identifier: Apache-2.0

package apollo.provider

import apollo.util.Crypto

/** AWS Signature Version 4 signing for a single HTTPS request
  * (`Authorization` header form), implemented directly on the portable
  * `Crypto` SHA-256/HMAC so it works identically on the JVM and Scala
  * Native — no AWS SDK, no `java.crypto`. Only what Bedrock Converse needs:
  * a POST with a JSON body, no query string.
  */
object SigV4:

  final case class Credentials(
      accessKeyId: String,
      secretAccessKey: String,
      sessionToken: Option[String]
  )

  /** Returns the headers to add to the request (Authorization + the signed
    * amz headers). `host` is the URL host (no scheme), `path` the canonical
    * URI, `amzDate` a `yyyyMMdd'T'HHmmss'Z'` UTC timestamp.
    */
  def signedHeaders(
      creds: Credentials,
      region: String,
      service: String,
      host: String,
      path: String,
      body: String,
      contentType: String,
      amzDate: String
  ): List[(String, String)] =
    val dateStamp     = amzDate.take(8) // yyyyMMdd
    val payloadHash   = Crypto.sha256Hex(body)
    val credScope     = s"$dateStamp/$region/$service/aws4_request"

    // Canonical headers must be sorted by lowercased name; we fix the set.
    val baseHeaders = List(
      "content-type" -> contentType,
      "host"         -> host,
      "x-amz-content-sha256" -> payloadHash,
      "x-amz-date"   -> amzDate
    ) ++ creds.sessionToken.map(t => "x-amz-security-token" -> t).toList
    val sorted        = baseHeaders.sortBy(_._1)
    val canonicalHeaders = sorted.map((k, v) => s"$k:${v.trim}\n").mkString
    val signedHeaderNames = sorted.map(_._1).mkString(";")

    val canonicalRequest = List(
      "POST", path, "", canonicalHeaders, signedHeaderNames, payloadHash
    ).mkString("\n")

    val stringToSign = List(
      "AWS4-HMAC-SHA256", amzDate, credScope, Crypto.sha256Hex(canonicalRequest)
    ).mkString("\n")

    val signingKey = deriveKey(creds.secretAccessKey, dateStamp, region, service)
    val signature  = Crypto.hex(Crypto.hmacSha256(signingKey, stringToSign))

    val authorization =
      s"AWS4-HMAC-SHA256 Credential=${creds.accessKeyId}/$credScope, " +
        s"SignedHeaders=$signedHeaderNames, Signature=$signature"

    (baseHeaders :+ ("authorization" -> authorization))
  end signedHeaders

  /** Signing-key derivation, exposed for the AWS test-vector check. */
  def deriveKey(secret: String, dateStamp: String, region: String, service: String): Array[Byte] =
    val kDate    = Crypto.hmacSha256(s"AWS4$secret".getBytes("UTF-8"), dateStamp)
    val kRegion  = Crypto.hmacSha256(kDate, region)
    val kService = Crypto.hmacSha256(kRegion, service)
    Crypto.hmacSha256(kService, "aws4_request")

  /** Current UTC timestamp in the `x-amz-date` basic format. */
  def amzDate(epochMillis: Long): String =
    val instant = java.time.Instant.ofEpochMilli(epochMillis)
    // Basic ISO-8601 (yyyyMMddTHHmmssZ), UTC, no separators.
    val s = instant.toString // e.g. 2026-09-05T12:34:56.789Z
    val digits = s.takeWhile(_ != '.') // strip fractional seconds if present
    val cleaned = if digits.endsWith("Z") then digits else digits + "Z"
    cleaned.replace("-", "").replace(":", "")
end SigV4

// SPDX-FileCopyrightText: 2026 David Greco <greco@acm.org>
//
// SPDX-License-Identifier: Apache-2.0

package apollo.mcp

import apollo.tools.ToolOutcome
import apollo.util.Jx
import apollo.util.Jx.*
import kyo.*
import kyo.Structure.Value

/** Conversion of `tools/call` results into the JSON string the model sees,
  * following the upstream `mcp_tool_handlers.py` / `mcp_tool_content.py`
  * rules: per-block rendering with media cached to files (`MEDIA:<path>`
  * markers), `structuredContent` forwarded only when no block rendered
  * usable text, protocol-reserved `_meta` keys stripped, and a hard
  * head+tail truncation far above the per-tool render budget.
  */
object McpContent:

  /** Upstream `_MCP_HARD_RESULT_CAP_CHARS`. */
  val hardResultCap = 2_000_000

  /** Upstream 50 MiB decoded cap for media/blob payloads. */
  private val maxBlobBytes  = 50L * 1024 * 1024
  private val maxBase64Len  = maxBlobBytes * 4 / 3 + 4

  /** Renders one `tools/call` result. `mediaDir` receives decoded media and
    * blob payloads; the server name feeds the read_resource hint in
    * resource-link markers.
    */
  def renderCallResult(server: String, result: Value, mediaDir: java.nio.file.Path): ToolOutcome =
    val blocks  = result.field("content").asArr.getOrElse(Chunk.empty).toList
    val isError = result.field("isError").asBool
      .orElse(result.field("is_error").asBool).getOrElse(false)
    if isError then
      val text = blocks.flatMap(errorText).mkString("\n").trim
      ToolOutcome.Error(truncate(if text.isEmpty then "MCP tool returned an error" else text))
    else
      val parts  = blocks.map(renderBlock(server, _, mediaDir))
      val usable = parts.exists(p => p.usable && p.text.trim.nonEmpty)
      val text   = truncate(parts.map(_.text).filter(_.nonEmpty).mkString("\n"))
      val meta   = result.field("_meta").asObj.map(filterMeta).filter(_.nonEmpty)
      val structured =
        if usable then Absent
        else result.field("structuredContent").filter(v => v != Value.Null)
      val payload = (structured, meta) match
        case (Present(s), _) =>
          val rendered = Jx.render(s)
          if rendered.length > hardResultCap then Jx.obj("result" -> Jx.str(truncate(rendered)))
          else Jx.obj("result" -> s)
        case (Absent, Present(m)) => Jx.obj("result" -> Jx.str(text), "_meta" -> Value.Record(m))
        case (Absent, Absent)     => Jx.obj("result" -> Jx.str(text))
      ToolOutcome.Ok(Jx.render(payload))
  end renderCallResult

  private final case class Part(text: String, usable: Boolean)

  private def renderBlock(server: String, block: Value, mediaDir: java.nio.file.Path): Part =
    val tpe = (block / "type").asStr.getOrElse("")
    (block / "text").asStr match
      case Present(text) if text.nonEmpty => Part(text, usable = true)
      case _ =>
        val mime = (block / "mimeType").asStr
        val data = (block / "data").asStr
        if data.nonEmpty && mime.exists(m => m.startsWith("image/") || m.startsWith("audio/")) then
          media(block, mediaDir, mime.getOrElse(""))
        else if tpe == "resource_link" || ((block / "uri").asStr.nonEmpty && block.field("resource").isEmpty) then
          val uri  = (block / "uri").asStr.getOrElse("")
          val name = (block / "name").asStr.getOrElse("")
          Part(
            s"[MCP resource link: uri=$uri, name=$name, mimeType=${mime.getOrElse("")} — " +
              s"fetch it with ${McpSchema.prefixedName(server, "read_resource")}]",
            usable = false
          )
        else
          block.field("resource") match
            case Present(res) => embeddedResource(res, mediaDir)
            case Absent =>
              Part(s"[MCP content dropped: unsupported block (type=$tpe" +
                mime.map(m => s", mimeType=$m").getOrElse("") + ")]", usable = false)
  end renderBlock

  private def media(block: Value, mediaDir: java.nio.file.Path, mime: String): Part =
    val b64 = (block / "data").asStr.getOrElse("")
    if b64.length > maxBase64Len then
      Part(s"[MCP audio resource too large to cache: ~${b64.length.toLong * 3 / 4} bytes]", usable = false)
    else
      decodeToFile(b64, mime, mediaDir) match
        case Present(path) => Part(s"MEDIA:$path", usable = true)
        case Absent        => Part("", usable = false)

  private def embeddedResource(res: Value, mediaDir: java.nio.file.Path): Part =
    (res / "text").asStr match
      case Present(text) => Part(text, usable = true)
      case Absent =>
        (res / "blob").asStr match
          case Absent => Part("[MCP embedded resource could not be decoded: no text or blob]", usable = false)
          case Present(b64) if b64.length > maxBase64Len =>
            Part(s"[MCP embedded resource too large to cache: ~${b64.length.toLong * 3 / 4} bytes]", usable = false)
          case Present(b64) =>
            val mime = (res / "mimeType").asStr.getOrElse("")
            decodeToFile(b64, mime, mediaDir) match
              case Present(path) =>
                val size = java.nio.file.Files.size(java.nio.file.Paths.get(path))
                val kind = if mime.isEmpty then "unknown type" else mime
                Part(s"[MCP resource saved to $path ($kind, $size bytes) — " +
                  "read it with read_file or terminal tools]", usable = true)
              case Absent =>
                Part("[MCP embedded resource could not be cached: write failed]", usable = false)

  private def decodeToFile(b64: String, mime: String, mediaDir: java.nio.file.Path): Maybe[String] =
    try
      val bytes = java.util.Base64.getDecoder.decode(b64)
      val ext = mime match
        case "image/png"  => ".png"
        case "image/jpeg" => ".jpg"
        case "image/gif"  => ".gif"
        case "image/webp" => ".webp"
        case "audio/wav"  => ".wav"
        case "audio/mpeg" => ".mp3"
        case "application/pdf" => ".pdf"
        case _            => ".bin"
      // Content-addressed name via MurmurHash3 (javalib MessageDigest is not
      // a safe bet on Native); length suffix keeps accidental collisions from
      // silently sharing a file.
      val digest = f"${scala.util.hashing.MurmurHash3.bytesHash(bytes)}%08x-${bytes.length}"
      java.nio.file.Files.createDirectories(mediaDir)
      val file = mediaDir.resolve(s"mcp-$digest$ext")
      java.nio.file.Files.write(file, bytes)
      Present(file.toString)
    catch case _: Exception => Absent

  private def errorText(block: Value): List[String] =
    (block / "text").asStr
      .orElse((block / "resource" / "text").asStr)
      .filter(_.nonEmpty).toList

  /** Upstream `_meta` filter: keys whose pre-`/` prefix contains a dot-label
    * `modelcontextprotocol` or `mcp` followed by at least one more label are
    * protocol-reserved and stripped (`modelcontextprotocol.io/x` reserved,
    * `com.example.mcp/x` kept).
    */
  private[mcp] def filterMeta(fields: Chunk[(String, Value)]): Chunk[(String, Value)] =
    fields.filterNot { (key, _) =>
      val prefix = key.split('/').head
      val labels = prefix.split('.').toList
      labels.init match
        case Nil  => false
        case rest => rest.contains("modelcontextprotocol") || rest.contains("mcp")
    }

  /** Upstream hard truncation: 40% head + 60% tail with the literal marker. */
  def truncate(text: String): String =
    if text.length <= hardResultCap then text
    else
      val head    = hardResultCap * 2 / 5
      val tail    = hardResultCap * 3 / 5
      val omitted = text.length - head - tail
      text.take(head) +
        s"\n\n... [MCP RESULT TRUNCATED - ${grouped(omitted)} chars omitted out of ${grouped(text.length)} total] ...\n\n" +
        text.takeRight(tail)

  /** Python `{:,}`-style thousands grouping (locale-independent). */
  private def grouped(n: Long): String =
    val digits = n.abs.toString
    val sign   = if n < 0 then "-" else ""
    sign + digits.reverse.grouped(3).mkString(",").reverse

  /** Upstream `_sanitize_error`: credential shapes redacted from error text
    * before the model sees it.
    */
  def redact(message: String): String =
    List(
      """ghp_[A-Za-z0-9]{20,}""".r,
      """sk-[A-Za-z0-9_-]{16,}""".r,
      """(?i)Bearer\s+[A-Za-z0-9._~+/=-]{8,}""".r,
      """(?i)\b(token|key|api_key|password|secret)=[^\s&"']+""".r
    ).foldLeft(message)((acc, r) => r.replaceAllIn(acc, "[REDACTED]"))
end McpContent

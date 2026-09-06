package apollo.lsp

import apollo.config.ApolloConfig
import apollo.util.Jx
import apollo.util.Jx.*
import kyo.*
import kyo.Structure.Value

/** Language Server Protocol client plumbing (pure): `Content-Length`-framed
  * JSON-RPC codec, request/notification builders, diagnostic parsing, and the
  * server registry (extension → language id + launch command). apollo is an LSP
  * *client* — it spawns real language servers to surface diagnostics for an
  * edited file (mirrors Hermes's LSP-backed lint). Live I/O is in `LspClient`. */
object Lsp:

  final case class Diagnostic(severity: Int, line: Int, character: Int, message: String):
    def sev: String = severity match
      case 1 => "error"; case 2 => "warning"; case 3 => "info"; case _ => "hint"
    def render: String = s"$sev [${line + 1}:${character + 1}] $message"

  final case class Server(languageId: String, argv: List[String])

  // --- framing -------------------------------------------------------------

  /** Frame a JSON message with an LSP `Content-Length` header. */
  def encode(json: String): String =
    s"Content-Length: ${json.getBytes("UTF-8").length}\r\n\r\n$json"

  /** Extracts complete frames from a buffer, returning them + the remainder.
    * (Char-based; adequate for ASCII JSON-RPC diagnostics.) */
  def decodeFrames(buffer: String): (List[String], String) =
    val out = List.newBuilder[String]
    var buf = buffer
    var go  = true
    while go do
      val sep = buf.indexOf("\r\n\r\n")
      if sep < 0 then go = false
      else
        val header = buf.substring(0, sep)
        val len = header.linesIterator.map(_.trim)
          .find(_.toLowerCase.startsWith("content-length:"))
          .flatMap(_.split(":", 2).lift(1).map(_.trim)).flatMap(_.toIntOption)
        len match
          case Some(n) if buf.length - (sep + 4) >= n =>
            out += buf.substring(sep + 4, sep + 4 + n)
            buf = buf.substring(sep + 4 + n)
          case _ => go = false
    (out.result(), buf)

  // --- message builders ----------------------------------------------------

  def initialize(id: Long, rootUri: String): String =
    Jx.render(Jx.obj("jsonrpc" -> Jx.str("2.0"), "id" -> Jx.num(id), "method" -> Jx.str("initialize"),
      "params" -> Jx.obj("processId" -> Jx.nul, "rootUri" -> Jx.str(rootUri),
        "capabilities" -> Jx.obj("textDocument" -> Jx.obj("publishDiagnostics" -> Jx.obj())))))

  def initialized: String =
    Jx.render(Jx.obj("jsonrpc" -> Jx.str("2.0"), "method" -> Jx.str("initialized"), "params" -> Jx.obj()))

  def didOpen(uri: String, languageId: String, text: String): String =
    Jx.render(Jx.obj("jsonrpc" -> Jx.str("2.0"), "method" -> Jx.str("textDocument/didOpen"),
      "params" -> Jx.obj("textDocument" -> Jx.obj(
        "uri" -> Jx.str(uri), "languageId" -> Jx.str(languageId),
        "version" -> Jx.num(1L), "text" -> Jx.str(text)))))

  def shutdown(id: Long): String =
    Jx.render(Jx.obj("jsonrpc" -> Jx.str("2.0"), "id" -> Jx.num(id), "method" -> Jx.str("shutdown")))

  val exit: String =
    Jx.render(Jx.obj("jsonrpc" -> Jx.str("2.0"), "method" -> Jx.str("exit")))

  /** True + the diagnostics if `json` is a publishDiagnostics for `uri`. */
  def publishDiagnosticsFor(json: Value, uri: String): Option[List[Diagnostic]] =
    if (json / "method").asStr != Present("textDocument/publishDiagnostics") then None
    else if (json / "params" / "uri").asStr != Present(uri) then None
    else Some((json / "params" / "diagnostics").asArr.getOrElse(Chunk.empty).toList.flatMap { d =>
      (d / "message").asStr.toList.map { m =>
        Diagnostic(
          severity  = (d / "severity").asLong.map(_.toInt).getOrElse(1),
          line      = (d / "range" / "start" / "line").asLong.map(_.toInt).getOrElse(0),
          character = (d / "range" / "start" / "character").asLong.map(_.toInt).getOrElse(0),
          message   = m)
      }
    })

  // --- server registry -----------------------------------------------------

  /** Built-in language servers (must be installed / on PATH). */
  val defaultServers: List[(String, Server)] = List(
    "py"    -> Server("python", List("pyright-langserver", "--stdio")),
    "ts"    -> Server("typescript", List("typescript-language-server", "--stdio")),
    "tsx"   -> Server("typescriptreact", List("typescript-language-server", "--stdio")),
    "js"    -> Server("javascript", List("typescript-language-server", "--stdio")),
    "go"    -> Server("go", List("gopls")),
    "rs"    -> Server("rust", List("rust-analyzer")),
    "c"     -> Server("c", List("clangd")),
    "cpp"   -> Server("cpp", List("clangd")),
    "scala" -> Server("scala", List("metals")),
    "sh"    -> Server("shellscript", List("bash-language-server", "start")),
    "lua"   -> Server("lua", List("lua-language-server")))

  def extensionOf(path: String): String =
    val name = path.substring(path.lastIndexOf('/') + 1)
    val dot  = name.lastIndexOf('.')
    if dot < 0 then "" else name.substring(dot + 1).toLowerCase

  /** The server for a file: config `lsp.servers.<ext>` command override wins,
    * else a built-in. */
  def serverFor(path: String, config: ApolloConfig): Option[Server] =
    val ext = extensionOf(path)
    val override0 = config.lspServerCommand(ext)
    override0 match
      case Present(argv) if argv.nonEmpty =>
        Some(Server(defaultServers.collectFirst { case (e, s) if e == ext => s.languageId }.getOrElse(ext), argv))
      case _ => defaultServers.collectFirst { case (e, s) if e == ext => s }
end Lsp

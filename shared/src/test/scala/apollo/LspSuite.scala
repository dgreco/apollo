package apollo.lsp

import apollo.config.{ApolloConfig, ApolloPaths, EnvChain, Yaml}
import apollo.util.Jx
import kyo.*

class LspSuite extends munit.FunSuite:

  private def run[A](v: A < (Sync & Async)): A =
    import AllowUnsafe.embrace.danger
    KyoApp.Unsafe.runAndBlock(20.seconds)(v).getOrThrow

  private def cfg(yaml: String = ""): ApolloConfig =
    ApolloConfig(if yaml.isEmpty then Absent else Present(Yaml.parse(yaml).getOrElse(throw new AssertionError("yaml"))),
      EnvChain(Map.empty), ApolloPaths(java.nio.file.Paths.get("/tmp/lsp-test")))

  test("encode + decodeFrames round-trip, streaming and partial") {
    val f1 = Lsp.encode("""{"a":1}""")
    val f2 = Lsp.encode("""{"b":2}""")
    assert(f1.startsWith("Content-Length: 7\r\n\r\n"), f1)
    val (frames, rest) = Lsp.decodeFrames(f1 + f2 + "Content-Length: 5\r\n\r\n{\"c\"")
    assertEquals(frames, List("""{"a":1}""", """{"b":2}"""))
    assert(rest.startsWith("Content-Length: 5"), rest) // incomplete third frame retained
    assertEquals(Lsp.decodeFrames("no frame yet")._1, Nil)
  }

  test("message builders carry the right methods/fields") {
    val init = Jx.parse(Lsp.initialize(1L, "file:///proj")).getOrElse(Jx.obj())
    import Jx.*
    assertEquals((init / "method").asStr, Present("initialize"))
    assertEquals((init / "params" / "rootUri").asStr, Present("file:///proj"))
    val open = Jx.parse(Lsp.didOpen("file:///a.py", "python", "x=1")).getOrElse(Jx.obj())
    assertEquals((open / "method").asStr, Present("textDocument/didOpen"))
    assertEquals((open / "params" / "textDocument" / "languageId").asStr, Present("python"))
  }

  test("publishDiagnosticsFor matches uri and parses ranges/severity") {
    val note = Jx.parse(
      """{"method":"textDocument/publishDiagnostics","params":{"uri":"file:///a.py","diagnostics":[
        {"severity":1,"range":{"start":{"line":4,"character":2}},"message":"undefined name"}]}}""").getOrElse(Jx.obj())
    Lsp.publishDiagnosticsFor(note, "file:///a.py") match
      case Some(List(d)) =>
        assertEquals(d.severity, 1); assertEquals(d.line, 4); assertEquals(d.character, 2)
        assertEquals(d.message, "undefined name"); assertEquals(d.sev, "error")
        assert(d.render.contains("[5:3] undefined name"), d.render) // 1-based in render
      case other => fail(s"expected one diagnostic, got $other")
    assertEquals(Lsp.publishDiagnosticsFor(note, "file:///other.py"), None) // uri mismatch
    assertEquals(Lsp.publishDiagnosticsFor(Jx.parse("""{"method":"window/logMessage"}""").getOrElse(Jx.obj()), "x"), None)
  }

  test("serverFor: built-ins + config override; extensionOf") {
    assertEquals(Lsp.extensionOf("/a/b/foo.py"), "py")
    assertEquals(Lsp.extensionOf("Makefile"), "")
    assertEquals(Lsp.serverFor("x.py", cfg()).map(_.argv.head), Some("pyright-langserver"))
    assertEquals(Lsp.serverFor("x.rs", cfg()).map(_.languageId), Some("rust"))
    assertEquals(Lsp.serverFor("x.unknownext", cfg()), None)
    // config override
    val c = cfg("lsp: {servers: {py: [\"my-pyls\", \"--stdio\"]}}")
    assertEquals(Lsp.serverFor("x.py", c).map(_.argv), Some(List("my-pyls", "--stdio")))
  }

  test("diagnose fails cleanly for a file with no configured server") {
    assert(run(LspClient.diagnose("/tmp/whatever.unknownext", cfg())).isFailure)
    assert(run(LspClient.diagnose("/tmp/x.py", cfg("lsp: {enabled: false}"))).isFailure) // disabled
  }
end LspSuite

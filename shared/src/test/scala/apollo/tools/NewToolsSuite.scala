package apollo.tools

import apollo.config.{ApolloConfig, ApolloPaths, EnvChain}
import kyo.*

/** execute_code (codeCommand mapping + a real sh run) and tool_search. */
class NewToolsSuite extends munit.FunSuite:

  private def run[A](v: A < (Sync & Async)): A =
    import AllowUnsafe.embrace.danger
    KyoApp.Unsafe.runAndBlock(20.seconds)(v).getOrThrow

  private def ctx(): ToolContext =
    val home   = java.nio.file.Files.createTempDirectory("apollo-newtools")
    val paths  = ApolloPaths(home)
    val config = ApolloConfig(Absent, EnvChain(Map.empty), paths)
    val todo   = run(AtomicRef.init(List.empty[TodoItem]))
    ToolContext(
      config = config, paths = paths, cwd = home, platform = "cli", sessionId = "nt",
      approvals = new ApprovalService(config, paths, "cli", oneShot = false, yoloFlag = true),
      ui = apollo.cli.UnattendedToolUi, todo = todo,
      skills = new apollo.skills.SkillStore(config, paths))

  private def dispatch(name: String, json: String): (String, Boolean) =
    run(ToolRegistry.dispatch(name, json, ctx()))

  // --- execute_code ---------------------------------------------------------

  test("codeCommand maps languages and quotes the snippet") {
    assertEquals(TerminalTools.codeCommand("python", "print(1)"), Some("python3 -c 'print(1)'"))
    assertEquals(TerminalTools.codeCommand("py", "x"), Some("python3 -c 'x'"))
    assertEquals(TerminalTools.codeCommand("node", "x"), Some("node -e 'x'"))
    assertEquals(TerminalTools.codeCommand("javascript", "x"), Some("node -e 'x'"))
    assertEquals(TerminalTools.codeCommand("ruby", "x"), Some("ruby -e 'x'"))
    assertEquals(TerminalTools.codeCommand("bash", "x"), Some("bash -c 'x'"))
    assertEquals(TerminalTools.codeCommand("cobol", "x"), None)
    // embedded single quote is escaped
    assertEquals(TerminalTools.codeCommand("sh", "echo 'hi'"), Some("""sh -c 'echo '\''hi'\'''"""))
  }

  test("execute_code runs a real sh snippet") {
    val (out, isErr) = dispatch("execute_code", """{"language":"sh","code":"echo hello-exec"}""")
    assert(!isErr, out)
    assert(out.contains("hello-exec"), out)
  }

  test("execute_code rejects an unsupported language and missing args") {
    assert(dispatch("execute_code", """{"language":"cobol","code":"x"}""")._1.contains("unsupported language"))
    assert(dispatch("execute_code", """{"code":"x"}""")._1.contains("missing required parameter"))
  }

  // --- tool_search ----------------------------------------------------------

  test("ToolSearchTool.search filters, sorts, and limits") {
    val entries = List(
      ("read_file", "file", "Read a file"),
      ("write_file", "file", "Write a file"),
      ("web_search", "web", "Search the web"))
    val hits = ToolSearchTool.search(entries, "file", 10)
    assertEquals(hits.map(_._1), List("read_file", "write_file"))            // matched + sorted
    assertEquals(ToolSearchTool.search(entries, "", 2).length, 2)            // empty query = all, limited
    assertEquals(ToolSearchTool.search(entries, "search", 10).map(_._1), List("web_search")) // desc match
    assertEquals(ToolSearchTool.search(entries, "zzz", 10), Nil)
  }

  test("tool_search dispatch finds built-in tools") {
    val (out, isErr) = dispatch("tool_search", """{"query":"read"}""")
    assert(!isErr, out)
    assert(out.contains("read_file"), out)
    assert(dispatch("tool_search", """{"query":"zzzznope"}""")._1.contains("no tools match"))
  }
end NewToolsSuite

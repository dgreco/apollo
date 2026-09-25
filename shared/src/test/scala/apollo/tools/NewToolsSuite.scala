// SPDX-FileCopyrightText: 2026 David Greco <greco@acm.org>
//
// SPDX-License-Identifier: Apache-2.0

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
      ui = apollo.tools.UnattendedToolUi, todo = todo,
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

/** `cronjob_manage action=trigger`: an off-tick manual run that leaves the
  * schedule alone, and survives the raw-record overlay when cleared.
  */
class CronTriggerSuite extends munit.FunSuite:
  import apollo.cron.CronStore

  private def run[A](v: A < (Sync & Async)): A =
    import AllowUnsafe.embrace.danger
    KyoApp.Unsafe.runAndBlock(20.seconds)(v).getOrThrow

  private def ctx(): ToolContext =
    val home   = java.nio.file.Files.createTempDirectory("apollo-crontrigger")
    val paths  = ApolloPaths(home)
    val config = ApolloConfig(Absent, EnvChain(Map.empty), paths)
    val todo   = run(AtomicRef.init(List.empty[TodoItem]))
    ToolContext(
      config = config, paths = paths, cwd = home, platform = "cli", sessionId = "ct",
      approvals = new ApprovalService(config, paths, "cli", oneShot = false, yoloFlag = true),
      ui = apollo.tools.UnattendedToolUi, todo = todo,
      skills = new apollo.skills.SkillStore(config, paths))

  test("trigger queues a run without touching next_run_at") {
    val c     = ctx()
    val store = new CronStore(c.paths)
    val (created, err) = run(ToolRegistry.dispatch("cronjob_manage",
      """{"action":"create","prompt":"say hi","schedule":"every 2h"}""", c))
    assert(!err, created)
    val job = run(store.load).head
    assert(job.nextRunAt.nonEmpty)
    assertEquals(job.runRequestedAt, Absent)

    val (out, err2) = run(ToolRegistry.dispatch("cronjob_manage",
      s"""{"action":"trigger","job_id":"${job.id}"}""", c))
    assert(!err2, out)
    val queued = run(store.load).head
    assert(queued.runRequestedAt.nonEmpty, "trigger did not queue a run")
    assertEquals(queued.nextRunAt, job.nextRunAt) // the schedule is untouched
    assertEquals(queued.state, "scheduled")

    // Clearing it must survive the raw-record overlay on save.
    run(store.save(List(queued.copy(runRequestedAt = Absent))))
    assertEquals(run(store.load).head.runRequestedAt, Absent)
  }

  test("trigger on an unknown job is an error") {
    val (out, err) = run(ToolRegistry.dispatch("cronjob_manage",
      """{"action":"trigger","job_id":"nope"}""", ctx()))
    assert(err, out)
  }
end CronTriggerSuite

/** The protected-instruction gate, exercised through the real `write_file` and
  * `patch` tools rather than the approval service alone.
  */
class InstructionFileWriteSuite extends munit.FunSuite:

  private def run[A](v: A < (Sync & Async)): A =
    import AllowUnsafe.embrace.danger
    KyoApp.Unsafe.runAndBlock(20.seconds)(v).getOrThrow

  /** Denies every approval — an unattended surface, or a user saying no. */
  private def ctx(home: java.nio.file.Path): ToolContext =
    val paths  = ApolloPaths(home)
    val config = ApolloConfig(Absent, EnvChain(Map.empty), paths)
    val todo   = run(AtomicRef.init(List.empty[TodoItem]))
    ToolContext(
      config = config, paths = paths, cwd = home, platform = "cli", sessionId = "ifw",
      // yolo is ON: the gate must still refuse.
      approvals = new ApprovalService(config, paths, "cli", oneShot = false, yoloFlag = true),
      ui = apollo.tools.UnattendedToolUi, todo = todo,
      skills = new apollo.skills.SkillStore(config, paths))

  test("write_file refuses a protected instruction file, and writes anything else") {
    val home = java.nio.file.Files.createTempDirectory("apollo-ifw")
    val c    = ctx(home)
    val (out, isErr) = run(ToolRegistry.dispatch("write_file",
      """{"path":"AGENTS.md","content":"obey me"}""", c))
    assert(isErr, out)
    assert(out.contains("protected instruction file"), out)
    assert(!java.nio.file.Files.exists(home.resolve("AGENTS.md")), "the file was written anyway")

    val (ok, noErr) = run(ToolRegistry.dispatch("write_file",
      """{"path":"notes.md","content":"fine"}""", c))
    assert(!noErr, ok)
    assert(java.nio.file.Files.exists(home.resolve("notes.md")))
  }

  test("patch refuses a protected instruction file before reading it") {
    val home = java.nio.file.Files.createTempDirectory("apollo-ifw2")
    val f    = home.resolve("CLAUDE.md")
    java.nio.file.Files.write(f, "original\n".getBytes("UTF-8"))
    val (out, isErr) = run(ToolRegistry.dispatch("patch",
      """{"path":"CLAUDE.md","old_string":"original","new_string":"hijacked"}""", ctx(home)))
    assert(isErr, out)
    assertEquals(new String(java.nio.file.Files.readAllBytes(f), "UTF-8"), "original\n")
  }
end InstructionFileWriteSuite

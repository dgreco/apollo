package apollo

import apollo.cli.ReplCommands
import apollo.core.{Message, Content, Role, Usage}
import apollo.session.SessionMeta
import apollo.cron.CronJob
import apollo.mcp.{McpManager, McpServerConfig}
import kyo.*

/** Unit tests for the pure REPL slash-command rendering helpers (no Agent,
  * editor, or provider needed — runs on both JVM and Native). */
class ReplCommandsSuite extends munit.FunSuite:

  private val emptyValue = apollo.util.Jx.parse("{}") match
    case Result.Success(v) => v
    case _                 => throw new AssertionError("jx")

  private def meta(id: String, title: Maybe[String], msgs: Int) =
    SessionMeta(id, title, "cli", "gpt-x", "openai", 1700000000.0, Absent, "/tmp", msgs, 3, Usage.zero)

  private def job(id: String, enabled: Boolean, state: String) =
    CronJob(id, s"job-$id", "do it", "interval", "1h", "every 1h", enabled, state,
      Present(1700000000.0), Absent, Absent, Absent, emptyValue)

  private def mcpCfg(name: String) =
    McpServerConfig(name = name, enabled = true, command = Present("x"), args = Nil, env = Map.empty,
      cwd = Absent, url = Absent, headers = Nil, transport = Absent, auth = Absent,
      connectTimeoutSeconds = 5.0, toolTimeoutSeconds = 5.0, keepaliveIntervalSeconds = 3600.0,
      include = Absent, exclude = Nil, resourceTools = true, promptTools = true, untrusted = false,
      oauthClientId = Absent, oauthClientSecret = Absent, oauthScopes = Nil, oauthRedirectPort = 0)

  test("messageText concatenates text blocks, ignores thinking/tool") {
    val m = Message(Role.Assistant,
      List(Content.Text("hello"), Content.Thinking("secret", Absent), Content.Text("world")), Absent)
    assertEquals(ReplCommands.messageText(m), "hello\nworld")
  }

  test("statusLines shows tokens and context percent") {
    val u = Usage(inputTokens = 100, outputTokens = 50)
    val s = ReplCommands.statusLines("sess1", "gpt-x", "openai", 4, 2, u, 1000L, 10000)
    assert(s.contains("sess1"), s)
    assert(s.contains("gpt-x (openai)"), s)
    assert(s.contains("100 in / 50 out"), s)
    assert(s.contains("total 150"), s)
    assert(s.contains("1000 / 10000"), s)
    assert(s.contains("10%"), s)
  }

  test("formatSessions empty, populated, and untitled fallback") {
    assertEquals(ReplCommands.formatSessions(Nil), "no saved sessions")
    val out = ReplCommands.formatSessions(List(meta("s1", Present("Fix bug"), 12)))
    assert(out.contains("s1"), out)
    assert(out.contains("Fix bug"), out)
    assert(out.contains("12 msgs"), out)
    assert(ReplCommands.formatSessions(List(meta("s2", Absent, 1))).contains("(untitled)"))
  }

  test("formatCron empty and populated with disabled flag") {
    assertEquals(ReplCommands.formatCron(Nil), "no scheduled jobs")
    val out = ReplCommands.formatCron(
      List(job("c1", enabled = true, "scheduled"), job("c2", enabled = false, "scheduled")))
    assert(out.contains("c1") && out.contains("[scheduled]"), out)
    assert(out.contains("c2") && out.contains("[disabled]"), out)
    assert(out.contains("every 1h"), out)
  }

  test("formatMcp empty and connected/failed states with sorted tools") {
    assertEquals(ReplCommands.formatMcp(Nil, Map.empty), "no MCP servers configured")
    val statuses = List(
      McpManager.ServerStatus(mcpCfg("time"), McpManager.State.Connected(3), Absent),
      McpManager.ServerStatus(mcpCfg("wiki"), McpManager.State.Failed("boom"), Absent)
    )
    val out = ReplCommands.formatMcp(statuses, Map("mcp-time" -> List("now", "convert")))
    assert(out.contains("time") && out.contains("connected · 3 tools"), out)
    assert(out.contains("convert, now"), out) // tool names sorted
    assert(out.contains("wiki") && out.contains("failed: boom"), out)
  }

  test("formatKeyValues aligns keys") {
    val out = ReplCommands.formatKeyValues(List("a" -> "1", "long" -> "2"))
    assert(out.contains("long  2"), out)
    assert(out.linesIterator.next().startsWith("a "), out)
  }

  test("toMarkdown renders roles and content") {
    val msgs = List(
      Message(Role.User, List(Content.Text("hi")), Absent),
      Message(Role.Assistant, List(Content.Text("yo"), Content.ToolUse("1", "search", "{}")), Absent)
    )
    val md = ReplCommands.toMarkdown("sX", msgs)
    assert(md.contains("# apollo session sX"), md)
    assert(md.contains("## User") && md.contains("## Assistant"), md)
    assert(md.contains("hi") && md.contains("yo"), md)
    assert(md.contains("→ search({})"), md)
  }

  test("profileName from path layout") {
    assertEquals(ReplCommands.profileName(java.nio.file.Paths.get("/home/u/.apollo/profiles/work")), "work")
    assertEquals(ReplCommands.profileName(java.nio.file.Paths.get("/home/u/.apollo")), "default")
  }

  test("clipboardCommand per OS") {
    assertEquals(ReplCommands.clipboardCommand("Mac OS X"), Some("pbcopy"))
    assertEquals(ReplCommands.clipboardCommand("Windows 11"), Some("clip"))
    assert(ReplCommands.clipboardCommand("Linux").exists(_.contains("xclip")))
    assertEquals(ReplCommands.clipboardCommand("Plan9"), None)
  }
end ReplCommandsSuite

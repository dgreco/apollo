package apollo

import apollo.agent.{Alternation, Compression}
import apollo.core.*
import kyo.{Absent, Present}

class AlternationSuite extends munit.FunSuite:

  private def toolCall(id: String): Content.ToolUse       = Content.ToolUse(id, "terminal", "{}")
  private def toolResult(id: String): Content.ToolResult  = Content.ToolResult(id, "ok", false)

  test("consecutive user messages are merged") {
    val repaired = Alternation.repair(List(Message.user("first"), Message.user("second")))
    assertEquals(repaired.length, 1)
    assertEquals(
      repaired.head.content.collectFirst { case Content.Text(t) => t },
      Some("first\n\nsecond")
    )
  }

  test("assistant tool calls without results get stub results") {
    val repaired = Alternation.repair(List(
      Message.user("go"),
      Message(Role.Assistant, List(toolCall("c1"), toolCall("c2")))
    ))
    val results = repaired.last.content.collect { case tr: Content.ToolResult => tr }
    assertEquals(results.map(_.toolUseId).toSet, Set("c1", "c2"))
    assert(results.forall(_.isError))
  }

  test("partial result messages are completed with stubs") {
    val repaired = Alternation.repair(List(
      Message.user("go"),
      Message(Role.Assistant, List(toolCall("c1"), toolCall("c2"))),
      Message.toolResults(List(toolResult("c1")))
    ))
    val results = repaired.last.content.collect { case tr: Content.ToolResult => tr }
    assertEquals(results.map(_.toolUseId).toSet, Set("c1", "c2"))
  }

  test("valid alternation passes through unchanged") {
    val msgs = List(
      Message.user("q"),
      Message(Role.Assistant, List(toolCall("c1"))),
      Message.toolResults(List(toolResult("c1"))),
      Message.assistant("done")
    )
    assertEquals(Alternation.repair(msgs), msgs)
  }

class CompressionSuite extends munit.FunSuite:

  test("phase 1 prunes only OLD large tool results") {
    val big = "x" * 1000
    val messages = List(
      Message.user("start"),
      Message.toolResults(List(Content.ToolResult("a", big, false))), // old
      Message.assistant("mid"),
      Message.toolResults(List(Content.ToolResult("b", big, false)))  // recent (protected)
    )
    val pruned = Compression.pruneOldToolResults(messages, protectLastN = 2)
    val first  = pruned(1).content.collectFirst { case tr: Content.ToolResult => tr.output }.get
    val last   = pruned(3).content.collectFirst { case tr: Content.ToolResult => tr.output }.get
    assert(first.contains("cleared to save context space"))
    assertEquals(last, big)
  }

  test("phase 1 leaves small results alone") {
    val messages = List(
      Message.toolResults(List(Content.ToolResult("a", "tiny", false))),
      Message.user("x"), Message.user("y"), Message.user("z")
    )
    val pruned = Compression.pruneOldToolResults(messages, protectLastN = 2)
    assertEquals(
      pruned.head.content.collectFirst { case tr: Content.ToolResult => tr.output },
      Some("tiny")
    )
  }

class MessageModelSuite extends munit.FunSuite:

  test("messages round-trip through JSON (session persistence format)") {
    import kyo.*
    val msg = Message(
      Role.Assistant,
      List(
        Content.Thinking("hmm", Present("sig")),
        Content.Text("hello"),
        Content.ToolUse("id1", "terminal", """{"command":"ls"}"""),
        Content.ToolResult("id1", "out", true),
        Content.Image("image/png", "aGk=")
      ),
      timestamp = Present("2026-09-04T12:00:00Z")
    )
    val json    = Json.encode(msg)
    val decoded = Json.decode[Message](json)
    assertEquals(decoded, Result.succeed(msg))
  }

  test("usage accumulates") {
    val a = Usage(10, 20, 5, 1, 2)
    val b = Usage(1, 2, 3, 4, 5)
    assertEquals(a + b, Usage(11, 22, 8, 5, 7))
  }

class CliArgsSuite extends munit.FunSuite:
  import apollo.cli.CliArgs

  test("global flags parse with values and = forms") {
    val a = CliArgs.parse(List("-m", "glm-5.2", "--provider=zai", "--reasoning", "high", "--yolo"))
    assertEquals(a.model, Present("glm-5.2"))
    assertEquals(a.provider, Present("zai"))
    assertEquals(a.reasoning, Present("high"))
    assert(a.yolo)
    assertEquals(a.command, Absent)
  }

  test("value flags don't swallow the subcommand (--reasoning high chat)") {
    val a = CliArgs.parse(List("--reasoning", "high", "chat"))
    assertEquals(a.reasoning, Present("high"))
    assertEquals(a.command, Present("chat"))
  }

  test("oneshot and toolsets") {
    val a = CliArgs.parse(List("-z", "hello world", "-t", "web,file"))
    assertEquals(a.oneshot, Present("hello world"))
    assertEquals(a.toolsets, Present(List("web", "file")))
  }

  test("continue with and without a name") {
    assertEquals(CliArgs.parse(List("-c")).continueSession, Present(""))
    assertEquals(CliArgs.parse(List("-c", "myproj")).continueSession, Present("myproj"))
    assertEquals(CliArgs.parse(List("-c", "chat")).continueSession, Present(""))
  }

  test("subcommand args pass through") {
    val a = CliArgs.parse(List("config", "get", "model.provider"))
    assertEquals(a.command, Present("config"))
    assertEquals(a.commandArgs, List("get", "model.provider"))
  }

class CronSuite extends munit.FunSuite:
  import apollo.cron.{CronExpr, Schedule}
  import java.time.Instant

  private val base = Instant.parse("2026-09-04T10:00:00Z")

  test("relative and interval schedules parse") {
    assert(Schedule.parse("30m", base).isSuccess)
    assert(Schedule.parse("in 2h", base).isSuccess)
    val Result_ = Schedule.parse("every 2h", base)
    assert(Result_.isSuccess)
    assertEquals(Result_.getOrElse(("", "", Absent))._1, "interval")
  }

  test("cron expressions evaluate the next occurrence") {
    val expr = CronExpr.parse("0 9 * * *").getOrElse(fail("parse"))
    val next = expr.next(base)
    assert(next.isAfter(base))
    val zoned = next.atZone(java.time.ZoneId.systemDefault)
    assertEquals(zoned.getHour, 9)
    assertEquals(zoned.getMinute, 0)
  }

  test("cron field forms: steps, ranges, lists; dow 7 = sunday") {
    assert(CronExpr.parse("*/15 * * * *").isSuccess)
    assert(CronExpr.parse("0 9-17 * * 1-5").isSuccess)
    assert(CronExpr.parse("0 0 1,15 * 7").isSuccess)
    assert(CronExpr.parse("bad").isFailure)
    assert(CronExpr.parse("61 * * * *").isFailure)
  }

  test("interval advance re-arms; one-shot does not") {
    assert(Schedule.advance("interval", "every 2h", base).nonEmpty)
    assertEquals(Schedule.advance("relative", "30m", base), Absent)
    assertEquals(Schedule.advance("iso", "2026-09-04T10:00:00Z", base), Absent)
  }

class SkillSuite extends munit.FunSuite:
  import apollo.skills.Skill

  test("frontmatter parses name/description; body preserved") {
    val (front, body) = Skill.parseFrontmatter(
      """---
        |name: github
        |description: "GitHub via gh CLI."
        |created_by: "agent"
        |---
        |
        |# GitHub Skill
        |content here""".stripMargin
    )
    assertEquals(front.get("name"), Some("github"))
    assertEquals(front.get("description"), Some("GitHub via gh CLI."))
    assertEquals(front.get("created_by"), Some("agent"))
    assert(body.startsWith("# GitHub Skill"))
  }

  test("missing frontmatter yields empty map and full body") {
    val (front, body) = Skill.parseFrontmatter("# Just markdown")
    assert(front.isEmpty)
    assertEquals(body, "# Just markdown")
  }

class GatewaySessionKeySuite extends munit.FunSuite:
  import apollo.gateway.SessionKeys

  test("DM keys are isolated per chat, no participant suffix") {
    assertEquals(
      SessionKeys.build("telegram", "private", "123456789", Present("123456789"), true),
      "agent:main:telegram:private:123456789"
    )
  }

  test("group keys append the participant when group_sessions_per_user is on") {
    assertEquals(
      SessionKeys.build("telegram", "group", "-100987", Present("42"), true),
      "agent:main:telegram:group:-100987:42"
    )
    assertEquals(
      SessionKeys.build("telegram", "group", "-100987", Present("42"), false),
      "agent:main:telegram:group:-100987"
    )
  }

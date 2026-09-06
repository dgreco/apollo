package apollo.gateway

import apollo.config.{ApolloConfig, ApolloPaths, EnvChain}
import kyo.*

/** iMessage connector: the pure query/parse/AppleScript logic. The sqlite3 /
  * osascript subprocesses are live-only (macOS + Full Disk Access), so they are
  * exercised via the exact `-json` output shape rather than a real chat.db. */
class IMessageSuite extends munit.FunSuite:

  test("pollQuery filters inbound text after a rowid; maxRowIdQuery shape") {
    val q = IMessage.pollQuery(42L)
    assert(q.contains("is_from_me = 0"), q)
    assert(q.contains("message.ROWID > 42"), q)
    assert(q.contains("message.text IS NOT NULL"), q)
    assert(q.contains("ORDER BY message.ROWID ASC"), q)
    assert(IMessage.maxRowIdQuery.contains("MAX(ROWID)"), IMessage.maxRowIdQuery)
  }

  test("parseRows / parseMaxRowId read sqlite3 -json output") {
    val json = """[{"rowid":5,"sender":"+15551230000","text":"hi there"},
                   {"rowid":7,"sender":"friend@icloud.com","text":"yo"}]"""
    assertEquals(IMessage.parseRows(json), List(
      IMessage.Msg(5L, "+15551230000", "hi there"),
      IMessage.Msg(7L, "friend@icloud.com", "yo")))
    assertEquals(IMessage.parseRows("[]"), Nil)
    assertEquals(IMessage.parseMaxRowId("""[{"m":123}]"""), 123L)
    assertEquals(IMessage.parseMaxRowId("[]"), 0L)
  }

  test("escapeApple + sendScript quote safely") {
    assertEquals(IMessage.escapeApple("""a "b" \c"""), """a \"b\" \\c""")
    val s = IMessage.sendScript("+15551230000", "hello \"world\"")
    assert(s.contains("tell application \"Messages\""), s)
    assert(s.contains("service type = iMessage"), s)
    assert(s.contains("+15551230000"), s)
    assert(s.contains("""send "hello \"world\"""""), s) // text quotes escaped
  }

  test("dbPath defaults under user home, honors IMESSAGE_DB") {
    val d = ApolloConfig(Absent, EnvChain(Map.empty), ApolloPaths(java.nio.file.Paths.get("/tmp/x")))
    assert(IMessage.dbPath(d).endsWith("/Library/Messages/chat.db"), IMessage.dbPath(d))
    val d2 = ApolloConfig(Absent, EnvChain(Map("IMESSAGE_DB" -> "/custom/chat.db")),
      ApolloPaths(java.nio.file.Paths.get("/tmp/x")))
    assertEquals(IMessage.dbPath(d2), "/custom/chat.db")
  }
end IMessageSuite

package apollo.gateway

import apollo.config.ApolloConfig
import apollo.util.Jx
import apollo.util.Jx.*
import kyo.*
import kyo.Structure.Value

/** iMessage connector (macOS only, no API): inbound messages are read by
  * polling the Messages SQLite database (`~/Library/Messages/chat.db`) via the
  * `sqlite3` CLI in `-json` mode; replies are sent by driving Messages with
  * `osascript` (AppleScript). Both are subprocesses, so it works on JVM and
  * Native alike. Requires Full Disk Access (to read chat.db) and Automation
  * permission (to control Messages); opt-in via `IMESSAGE_ENABLED`. */
object IMessage:

  private val maxMessage = 8000
  final case class Msg(rowId: Long, sender: String, text: String)

  def services(config: ApolloConfig, hub: SessionHub): List[Unit < (Sync & Async)] =
    List(bootstrap(config, hub))

  private def bootstrap(config: ApolloConfig, hub: SessionHub): Unit < (Sync & Async) =
    val db = dbPath(config)
    Console.printLine(s"imessage: watching $db").andThen {
      sqlite(db, maxRowIdQuery).map {
        case Result.Success(out) => poll(config, hub, db, parseMaxRowId(out))
        case Result.Failure(e) =>
          Console.printLine(s"imessage: cannot read chat.db ($e); is Full Disk Access granted?")
      }
    }

  private def poll(config: ApolloConfig, hub: SessionHub, db: String, afterRowId: Long): Unit < (Sync & Async) =
    val interval = config.env.get("IMESSAGE_POLL_SECONDS").flatMap(p => Maybe.fromOption(p.toIntOption)).getOrElse(3).max(1)
    sqlite(db, pollQuery(afterRowId)).map {
      case Result.Success(out) =>
        val msgs = parseRows(out)
        val next = msgs.lastOption.map(_.rowId).getOrElse(afterRowId)
        Kyo.foreachDiscard(msgs)(m => Fiber.initUnscoped(handle(m, config, hub, db)).unit)
          .andThen(Async.sleep(interval.seconds))
          .andThen(poll(config, hub, db, next))
      case Result.Failure(e) =>
        Console.printLine(s"imessage: poll error ($e); retry in 5s")
          .andThen(Async.sleep(5.seconds)).andThen(poll(config, hub, db, afterRowId))
    }

  // --- pure ----------------------------------------------------------------

  val maxRowIdQuery: String = "SELECT COALESCE(MAX(ROWID),0) as m FROM message"

  /** New inbound (not-from-me) text messages after `afterRowId`. */
  def pollQuery(afterRowId: Long): String =
    "SELECT message.ROWID as rowid, handle.id as sender, message.text as text " +
      "FROM message JOIN handle ON message.handle_id = handle.ROWID " +
      s"WHERE message.is_from_me = 0 AND message.ROWID > $afterRowId " +
      "AND message.text IS NOT NULL AND message.text != '' " +
      "ORDER BY message.ROWID ASC LIMIT 50"

  def parseMaxRowId(sqliteJson: String): Long =
    Jx.parse(sqliteJson).getOrElse(Jx.arr()).asArr.getOrElse(Chunk.empty).headOption match
      case Some(r) => (r / "m").asLong.getOrElse(0L)
      case None    => 0L

  def parseRows(sqliteJson: String): List[Msg] =
    Jx.parse(sqliteJson).getOrElse(Jx.arr()).asArr.getOrElse(Chunk.empty).toList.flatMap { r =>
      (for
        rid  <- (r / "rowid").asLong
        snd  <- (r / "sender").asStr
        txt  <- (r / "text").asStr
      yield Msg(rid, snd, txt)).toList
    }

  /** Escape a string for embedding in an AppleScript double-quoted literal. */
  def escapeApple(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")

  /** AppleScript that sends `text` to `recipient` over iMessage. */
  def sendScript(recipient: String, text: String): String =
    val r = escapeApple(recipient)
    val t = escapeApple(text)
    "tell application \"Messages\"\n" +
      "set targetService to 1st account whose service type = iMessage\n" +
      s"""set targetBuddy to participant "$r" of targetService\n""" +
      s"""send "$t" to targetBuddy\n""" +
      "end tell"

  def dbPath(config: ApolloConfig): String =
    config.env.get("IMESSAGE_DB").filter(_.nonEmpty).getOrElse {
      val home = Option(java.lang.System.getProperty("user.home")).getOrElse("~")
      s"$home/Library/Messages/chat.db"
    }

  // --- processing ----------------------------------------------------------

  private[gateway] def handle(m: Msg, config: ApolloConfig, hub: SessionHub, db: String): Unit < (Sync & Async) =
    if m.text.isEmpty then ()
    else if !authorized(config, m.sender) then
      send(m.sender, s"Not authorized. Add ${m.sender} to IMESSAGE_ALLOWED_USERS, or set IMESSAGE_ALLOW_ALL_USERS=1.")
    else
      val key = hub.sessionKey("imessage", "dm", m.sender, Present(m.sender))
      m.text.trim match
        case "/reset" | "/new" => hub.resetSession(key).andThen(send(m.sender, "Conversation cleared."))
        case "/status"         => send(m.sender, s"session: $key")
        case prompt =>
          Abort.run[Throwable](Abort.catching[Throwable](
            hub.turn(key, "imessage", prompt).map(reply => sendChunked(m.sender, reply))
          )).map {
            case Result.Success(_) => ()
            case _                 => send(m.sender, "Something went wrong; please try again.")
          }

  private def authorized(config: ApolloConfig, sender: String): Boolean =
    val env = config.env
    val allowAll = env.getBool("IMESSAGE_ALLOW_ALL_USERS").getOrElse(false) ||
      env.getBool("GATEWAY_ALLOW_ALL_USERS").getOrElse(false)
    val allowlist =
      (env.get("IMESSAGE_ALLOWED_USERS").map(_.split(",").toList).getOrElse(Nil)
        ++ env.get("GATEWAY_ALLOWED_USERS").map(_.split(",").toList).getOrElse(Nil)).map(_.trim).filter(_.nonEmpty)
    allowAll || allowlist.contains(sender) || allowlist.contains("*")

  private def sendChunked(to: String, text: String): Unit < (Sync & Async) =
    val chunks = text.grouped(maxMessage).toList match { case Nil => List("(empty response)"); case cs => cs }
    Kyo.foreachDiscard(chunks)(c => send(to, c))

  private def send(to: String, text: String): Unit < (Sync & Async) =
    osascript(sendScript(to, text)).map {
      case Result.Success(_) => ()
      case Result.Failure(e) => Console.printLine(s"imessage: send failed ($e); is Automation permission granted?")
    }

  // --- subprocesses (live only) --------------------------------------------

  private def sqlite(db: String, query: String): Result[String, String] < (Sync & Async) =
    Abort.run[CommandException](Command("sqlite3", "-json", db, query).text).map {
      case Result.Success(out) => Result.succeed(out)
      case Result.Failure(e)   => Result.fail(String.valueOf(e.getMessage))
      case Result.Panic(e)     => Result.fail(String.valueOf(e.getMessage))
    }

  private def osascript(script: String): Result[String, String] < (Sync & Async) =
    Abort.run[CommandException](Command("osascript", "-e", script).text).map {
      case Result.Success(out) => Result.succeed(out)
      case Result.Failure(e)   => Result.fail(String.valueOf(e.getMessage))
      case Result.Panic(e)     => Result.fail(String.valueOf(e.getMessage))
    }
end IMessage

package apollo.gateway

import kyo.*

/** JVM email transport: real IMAP (fetch unseen) and SMTP (send) over java
  * stdlib sockets + TLS (`javax.net.ssl`) — no extra dependency. The blocking
  * exchange runs inside a `Sync.defer` (called from the gateway's background
  * poll fiber). Protocol shaping is the shared pure `Email` helpers. */
object PlatformEmail:

  import java.net.Socket
  import javax.net.ssl.SSLSocketFactory

  private val soTimeoutMs = 30000

  private final class Conn(sock: Socket):
    private val in  = sock.getInputStream
    private val out = sock.getOutputStream
    def write(s: String): Unit = { out.write(s.getBytes("UTF-8")); out.flush() }
    /** Read one CRLF/LF-terminated line (without the terminator). */
    def readLine(): String =
      val buf = new java.io.ByteArrayOutputStream()
      var b = in.read()
      while b != -1 && b != '\n' do { if b != '\r' then buf.write(b); b = in.read() }
      new String(buf.toByteArray, "UTF-8")
    /** Read exactly `n` bytes as a UTF-8 string. */
    def readBytes(n: Int): String =
      val buf = new Array[Byte](n)
      var off = 0
      while off < n do
        val r = in.read(buf, off, n - off)
        if r == -1 then off = n else off += r
      new String(buf, "UTF-8")
    def close(): Unit = try sock.close() catch { case _: Throwable => () }

  private def connect(host: String, port: Int, tls: Boolean): Conn =
    val sock =
      if tls then SSLSocketFactory.getDefault.createSocket(host, port)
      else new Socket(host, port)
    sock.setSoTimeout(soTimeoutMs)
    new Conn(sock)

  // --- SMTP send -----------------------------------------------------------

  def send(cfg: Email.Config, to: String, subject: String, body: String): Result[String, Unit] < (Sync & Async) =
    Sync.defer {
      try
        val c = connect(cfg.smtpHost, cfg.smtpPort, cfg.smtpTls)
        try
          def expect(ok: Boolean, ctx: String): Either[String, Unit] =
            if ok then Right(()) else Left(s"SMTP $ctx failed")
          def reply(): String =
            var line = c.readLine()
            while !Email.smtpLineFinal(line) do line = c.readLine()
            line
          val steps: Either[String, Unit] =
            for
              _ <- expect(Email.smtpOk(reply()), "greeting")
              _ <- { c.write(s"EHLO apollo\r\n"); expect(Email.smtpOk(reply()), "EHLO") }
              _ <- { c.write("AUTH LOGIN\r\n"); expect(Email.smtpOk(reply()), "AUTH") }
              _ <- { c.write(Email.b64(cfg.user) + "\r\n"); expect(Email.smtpOk(reply()), "user") }
              _ <- { c.write(Email.b64(cfg.pass) + "\r\n"); expect(Email.smtpOk(reply()), "pass") }
              _ <- { c.write(s"MAIL FROM:<${cfg.from}>\r\n"); expect(Email.smtpOk(reply()), "MAIL FROM") }
              _ <- { c.write(s"RCPT TO:<$to>\r\n"); expect(Email.smtpOk(reply()), "RCPT TO") }
              _ <- { c.write("DATA\r\n"); expect(Email.smtpOk(reply()), "DATA") }
              _ <- {
                c.write(Email.messageData(cfg.from, to, subject, body) + "\r\n.\r\n")
                expect(Email.smtpOk(reply()), "message")
              }
            yield ()
          c.write("QUIT\r\n")
          steps match { case Right(_) => Result.succeed(()); case Left(e) => Result.fail(e) }
        finally c.close()
      catch case e: Throwable => Result.fail(s"SMTP error: ${e.getMessage}")
    }

  // --- IMAP fetch ----------------------------------------------------------

  def fetchUnseen(cfg: Email.Config): Result[String, List[Email.Msg]] < (Sync & Async) =
    Sync.defer {
      try
        val c = connect(cfg.imapHost, cfg.imapPort, cfg.imapTls)
        try
          val tag = new java.util.concurrent.atomic.AtomicInteger(0)
          def nextTag(): String = "a" + tag.incrementAndGet()
          /** Runs a command, returning all response lines up to the tagged OK
            * (throws on a non-OK completion). */
          def command(cmd: String): List[String] =
            val t = nextTag()
            c.write(s"$t $cmd\r\n")
            val lines = scala.collection.mutable.ListBuffer[String]()
            var done = false
            while !done do
              val line = c.readLine()
              if line.trim.startsWith(s"$t ") then
                if !Email.taggedOk(line, t) then throw new RuntimeException(s"IMAP '$cmd' -> $line")
                done = true
              else lines += line
            lines.toList
          // greeting
          c.readLine()
          command(s"LOGIN ${cfg.user} ${cfg.pass}")
          command("SELECT INBOX")
          val searchLines = command("SEARCH UNSEEN")
          val ids = Email.parseSearchIds(searchLines.mkString("\n"))
          val msgs = ids.flatMap { id => fetchOne(c, nextTag(), id) }
          // mark fetched messages seen so they aren't re-processed
          ids.foreach(id => try command(s"STORE $id +FLAGS (\\Seen)") catch { case _: Throwable => () })
          c.write(s"${nextTag()} LOGOUT\r\n")
          Result.succeed(msgs)
        finally c.close()
      catch case e: Throwable => Result.fail(s"IMAP error: ${e.getMessage}")
    }

  /** FETCH one message body as a literal and parse it. */
  private def fetchOne(c: Conn, tag: String, id: String): Option[Email.Msg] =
    c.write(s"$tag FETCH $id BODY.PEEK[]\r\n")
    var raw: Option[String] = None
    var done = false
    while !done do
      val line = c.readLine()
      Email.literalSize(line) match
        case Some(n) => raw = Some(c.readBytes(n)); // then the rest of the FETCH line(s) follow
        case None =>
          if line.trim.startsWith(s"$tag ") then done = true
    raw.map { r =>
      val (from, subject, body) = Email.parseMessage(r)
      Email.Msg(id, from, subject, body)
    }
end PlatformEmail

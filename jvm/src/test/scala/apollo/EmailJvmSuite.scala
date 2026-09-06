package apollo.gateway

import kyo.*

/** JVM email transport E2E: real PlatformEmail IMAP fetch + SMTP send against
  * mock TCP servers (plaintext) scripted with java.net.ServerSocket. */
class EmailJvmSuite extends munit.FunSuite:

  private def run[A](v: A < (Sync & Async)): A =
    import AllowUnsafe.embrace.danger
    KyoApp.Unsafe.runAndBlock(30.seconds)(v).getOrThrow

  /** Serve exactly one connection on an ephemeral port with `handler`. */
  private def serve(handler: (java.io.InputStream, java.io.OutputStream) => Unit): java.net.ServerSocket =
    val ss = new java.net.ServerSocket(0)
    val t = new Thread(() =>
      try { val s = ss.accept(); try handler(s.getInputStream, s.getOutputStream) finally s.close() }
      catch { case _: Throwable => () })
    t.setDaemon(true); t.start()
    ss

  private def readLine(in: java.io.InputStream): String =
    val buf = new java.io.ByteArrayOutputStream()
    var b = in.read()
    while b != -1 && b != '\n' do { if b != '\r' then buf.write(b); b = in.read() }
    new String(buf.toByteArray, "UTF-8")

  private def w(out: java.io.OutputStream, s: String): Unit = { out.write(s.getBytes("UTF-8")); out.flush() }

  test("fetchUnseen logs in, searches, fetches, and parses the message") {
    val rawMsg =
      "From: Ada <ada@example.com>\r\nSubject: Question\r\n\r\nWhat is 2+2?\r\n"
    val imap = serve { (in, out) =>
      w(out, "* OK IMAP ready\r\n")
      var alive = true
      while alive do
        val line = readLine(in)
        if line.isEmpty then alive = false
        else
          val tag = line.split("\\s+").headOption.getOrElse("a")
          val cmd = line.substring(tag.length).trim.toUpperCase
          if cmd.startsWith("LOGIN") then w(out, s"$tag OK LOGIN completed\r\n")
          else if cmd.startsWith("SELECT") then w(out, s"* 1 EXISTS\r\n$tag OK [READ-WRITE] SELECT completed\r\n")
          else if cmd.startsWith("SEARCH") then w(out, s"* SEARCH 1\r\n$tag OK SEARCH completed\r\n")
          else if cmd.startsWith("FETCH") then
            w(out, s"* 1 FETCH (BODY[] {${rawMsg.getBytes("UTF-8").length}}\r\n$rawMsg)\r\n$tag OK FETCH completed\r\n")
          else if cmd.startsWith("STORE") then w(out, s"* 1 FETCH (FLAGS (\\Seen))\r\n$tag OK STORE completed\r\n")
          else if cmd.startsWith("LOGOUT") then { w(out, s"* BYE\r\n$tag OK LOGOUT\r\n"); alive = false }
          else w(out, s"$tag OK\r\n")
    }
    try
      val cfg = Email.Config("127.0.0.1", imap.getLocalPort, imapTls = false,
        "127.0.0.1", 0, smtpTls = false, "u", "p", "u@example.com")
      val result = run(PlatformEmail.fetchUnseen(cfg))
      result match
        case Result.Success(msgs) =>
          assertEquals(msgs.length, 1, msgs.toString)
          assertEquals(msgs.head.from, "Ada <ada@example.com>")
          assertEquals(msgs.head.subject, "Question")
          assert(msgs.head.body.contains("2+2"), msgs.head.body)
        case other => fail(s"expected messages, got $other")
    finally imap.close()
  }

  test("send authenticates and delivers the message body over SMTP") {
    val captured2 = new java.util.concurrent.atomic.AtomicReference[String]("")
    val smtp2 = serve { (in, out) =>
      w(out, "220 mock ESMTP\r\n")
      var inData = false; var authStep = 0
      val data = new StringBuilder; var alive = true
      while alive do
        val line = readLine(in)
        if inData then
          if line == "." then { inData = false; captured2.set(data.toString); w(out, "250 OK queued\r\n") }
          else { data.append(line).append("\n") }
        else
          val u = line.toUpperCase
          if u.startsWith("EHLO") then w(out, "250-mock\r\n250 AUTH LOGIN\r\n")
          else if u.startsWith("AUTH LOGIN") then { authStep = 1; w(out, "334 VXNlcm5hbWU6\r\n") }
          else if authStep == 1 then { authStep = 2; w(out, "334 UGFzc3dvcmQ6\r\n") }
          else if authStep == 2 then { authStep = 3; w(out, "235 OK authenticated\r\n") }
          else if u.startsWith("MAIL FROM") then w(out, "250 OK\r\n")
          else if u.startsWith("RCPT TO") then w(out, "250 OK\r\n")
          else if u == "DATA" then { inData = true; w(out, "354 Go ahead\r\n") }
          else if u.startsWith("QUIT") then { w(out, "221 Bye\r\n"); alive = false }
          else w(out, "250 OK\r\n")
    }
    try
      val cfg = Email.Config("127.0.0.1", 0, imapTls = false,
        "127.0.0.1", smtp2.getLocalPort, smtpTls = false, "u", "p", "bot@example.com")
      val result = run(PlatformEmail.send(cfg, "ada@example.com", "Re: Question", "The answer is 4."))
      assert(result.isSuccess, result.toString)
      val sent = captured2.get
      assert(sent.contains("The answer is 4."), sent)
      assert(sent.contains("Subject: Re: Question"), sent)
      assert(sent.contains("To: ada@example.com"), sent)
    finally smtp2.close()
  }
end EmailJvmSuite

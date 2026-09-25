// SPDX-FileCopyrightText: 2026 David Greco <greco@acm.org>
//
// SPDX-License-Identifier: Apache-2.0

package apollo.gateway

import apollo.config.{ApolloConfig, ApolloPaths, EnvChain}
import kyo.*

/** Email connector: the pure IMAP/SMTP protocol helpers + config parsing
  * (tested on both platforms; the socket I/O lives in the JVM PlatformEmail). */
class EmailSuite extends munit.FunSuite:

  test("SMTP helpers: base64, reply-code classification, multiline continuation") {
    assertEquals(Email.b64("Aladdin:open sesame"), "QWxhZGRpbjpvcGVuIHNlc2FtZQ==")
    assert(Email.smtpOk("250 OK") && Email.smtpOk("334 go ahead"))
    assert(!Email.smtpOk("535 auth failed") && !Email.smtpOk("500 err"))
    assert(!Email.smtpLineFinal("250-first"))
    assert(Email.smtpLineFinal("250 last") && Email.smtpLineFinal("ok"))
  }

  test("messageData writes headers, CRLF, and dot-stuffs the body") {
    val d = Email.messageData("me@x.com", "you@y.com", "Hello", "line1\n.hidden\nbye")
    assert(d.contains("From: me@x.com"), d)
    assert(d.contains("Subject: Hello"), d)
    assert(d.contains("\r\n\r\n"), "blank line between headers and body")
    assert(d.contains("\r\n..hidden\r\n"), s"leading dot stuffed: $d")
  }

  test("IMAP helpers: literalSize, parseSearchIds, taggedOk") {
    assertEquals(Email.literalSize("* 1 FETCH (BODY[] {42}"), Some(42))
    assertEquals(Email.literalSize("a3 OK FETCH complete"), None)
    assertEquals(Email.parseSearchIds("* SEARCH 1 3 5\r\na1 OK Search completed"), List("1", "3", "5"))
    assertEquals(Email.parseSearchIds("* SEARCH\r\na1 OK"), Nil)
    assert(Email.taggedOk("a1 OK LOGIN completed", "a1"))
    assert(!Email.taggedOk("a1 NO login failed", "a1"))
  }

  test("parseMessage splits headers/body; replySubject; bareAddress") {
    val raw = "From: Ada <ada@x.com>\r\nSubject: Ping\r\nDate: today\r\n\r\nHello there\r\nsecond line\r\n"
    val (from, subject, body) = Email.parseMessage(raw)
    assertEquals(from, "Ada <ada@x.com>")
    assertEquals(subject, "Ping")
    assertEquals(body, "Hello there\nsecond line")
    assertEquals(Email.replySubject("Ping"), "Re: Ping")
    assertEquals(Email.replySubject("Re: Ping"), "Re: Ping")
    assertEquals(Email.bareAddress("Ada <ada@x.com>"), "ada@x.com")
    assertEquals(Email.bareAddress("bob@y.com"), "bob@y.com")
  }

  test("fromEnv requires host+user+pass; applies TLS-aware port defaults") {
    def cfg(env: Map[String, String]) =
      Email.fromEnv(ApolloConfig(Absent, EnvChain(env), ApolloPaths(java.nio.file.Paths.get("/tmp/x"))))
    assertEquals(cfg(Map("EMAIL_IMAP_HOST" -> "h")), Absent) // no user/pass
    cfg(Map("EMAIL_IMAP_HOST" -> "imap.x", "EMAIL_USER" -> "u", "EMAIL_PASSWORD" -> "p")) match
      case Present(c) =>
        assertEquals(c.imapPort, 993); assertEquals(c.smtpPort, 465)
        assertEquals(c.smtpHost, "imap.x"); assertEquals(c.from, "u")
        assert(c.imapTls && c.smtpTls)
      case Absent => fail("expected a config")
    // explicit plaintext ports
    cfg(Map("EMAIL_IMAP_HOST" -> "h", "EMAIL_USER" -> "u", "EMAIL_PASSWORD" -> "p",
      "EMAIL_IMAP_TLS" -> "0", "EMAIL_SMTP_TLS" -> "false")) match
      case Present(c) => assertEquals(c.imapPort, 143); assertEquals(c.smtpPort, 587)
      case Absent     => fail("expected a config")
  }
end EmailSuite

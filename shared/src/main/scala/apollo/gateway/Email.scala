// SPDX-FileCopyrightText: 2026 David Greco <greco@acm.org>
//
// SPDX-License-Identifier: Apache-2.0

package apollo.gateway

import apollo.config.ApolloConfig
import kyo.*

/** Email connector: poll an IMAP INBOX for unseen messages, run each through
  * the agent, and reply over SMTP. The wire protocols (IMAP + SMTP) are line
  * protocols over TLS; the socket I/O lives in the per-platform `PlatformEmail`
  * (real on the JVM via java stdlib sockets, a stub on Native, which has no
  * TLS-socket support). Everything protocol-shaped here is pure and tested on
  * both platforms. */
object Email:

  private val maxMessage = 40000

  final case class Config(
      imapHost: String, imapPort: Int, imapTls: Boolean,
      smtpHost: String, smtpPort: Int, smtpTls: Boolean,
      user: String, pass: String, from: String)

  final case class Msg(uid: String, from: String, subject: String, body: String)

  /** Reads EMAIL_* env into a Config, or Absent when not configured. IMAP host
    * + user + pass are required; SMTP defaults to the IMAP host's submission. */
  def fromEnv(config: ApolloConfig): Maybe[Config] =
    val env = config.env.get
    (env("EMAIL_IMAP_HOST"), env("EMAIL_USER"), env("EMAIL_PASSWORD")) match
      case (Present(imapHost), Present(user), Present(pass)) =>
        val imapTls  = env("EMAIL_IMAP_TLS").forall(b => b != "0" && b.toLowerCase != "false")
        val imapPort = env("EMAIL_IMAP_PORT").flatMap(p => Maybe.fromOption(p.toIntOption)).getOrElse(if imapTls then 993 else 143)
        val smtpHost = env("EMAIL_SMTP_HOST").getOrElse(imapHost)
        val smtpTls  = env("EMAIL_SMTP_TLS").forall(b => b != "0" && b.toLowerCase != "false")
        val smtpPort = env("EMAIL_SMTP_PORT").flatMap(p => Maybe.fromOption(p.toIntOption)).getOrElse(if smtpTls then 465 else 587)
        Present(Config(imapHost, imapPort, imapTls, smtpHost, smtpPort, smtpTls,
          user, pass, env("EMAIL_FROM").getOrElse(user)))
      case _ => Absent

  // --- pure protocol helpers (SMTP) ----------------------------------------

  def b64(s: String): String = java.util.Base64.getEncoder.encodeToString(s.getBytes("UTF-8"))

  /** True for an SMTP/IMAP-greeting positive reply line (2xx/3xx). */
  def smtpOk(line: String): Boolean = line.length >= 1 && (line(0) == '2' || line(0) == '3')

  /** An SMTP reply may span lines: `250-...` continues, `250 ...` ends. */
  def smtpLineFinal(line: String): Boolean = line.length < 4 || line(3) != '-'

  /** The DATA payload (headers + body), CRLF-terminated, with the body's lone
    * dots escaped (dot-stuffing) and a trailing `.` line appended by the caller. */
  def messageData(from: String, to: String, subject: String, body: String): String =
    val hdrs = List(
      s"From: $from", s"To: $to", s"Subject: $subject",
      "MIME-Version: 1.0", "Content-Type: text/plain; charset=UTF-8")
    val stuffed = body.replace("\r\n", "\n").split("\n", -1)
      .map(l => if l.startsWith(".") then "." + l else l).mkString("\r\n")
    hdrs.mkString("\r\n") + "\r\n\r\n" + stuffed

  // --- pure protocol helpers (IMAP) ----------------------------------------

  /** The trailing `{123}` literal byte-count of an IMAP line, if any. */
  def literalSize(line: String): Option[Int] =
    val t = line.trim
    if t.endsWith("}") then
      val i = t.lastIndexOf('{')
      if i >= 0 then t.substring(i + 1, t.length - 1).toIntOption else None
    else None

  /** Message sequence numbers from an untagged `* SEARCH 1 2 3` response. */
  def parseSearchIds(response: String): List[String] =
    response.linesIterator.filter(l => l.toUpperCase.startsWith("* SEARCH")).flatMap { l =>
      l.trim.split("\\s+").drop(2).filter(_.nonEmpty)
    }.toList

  /** True when `line` is the tagged completion `<tag> OK ...`. */
  def taggedOk(line: String, tag: String): Boolean =
    val t = line.trim
    t.startsWith(s"$tag OK") || t.startsWith(s"$tag ok")

  /** Splits a raw RFC822 message into (From, Subject, text body). Header
    * unfolding is minimal (enough for typical senders). */
  def parseMessage(raw: String): (String, String, String) =
    val norm = raw.replace("\r\n", "\n")
    val split = norm.indexOf("\n\n")
    val (headerBlock, body) = if split >= 0 then (norm.substring(0, split), norm.substring(split + 2)) else (norm, "")
    def header(name: String): String =
      headerBlock.linesIterator.find(_.toLowerCase.startsWith(name.toLowerCase + ":"))
        .map(_.substring(name.length + 1).trim).getOrElse("")
    (header("From"), header("Subject"), body.trim)

  def replySubject(subject: String): String =
    if subject.toLowerCase.startsWith("re:") then subject else s"Re: $subject"

  /** The bare address inside a `Name <addr@host>` (or the string itself). */
  def bareAddress(from: String): String =
    val lt = from.indexOf('<'); val gt = from.indexOf('>')
    if lt >= 0 && gt > lt then from.substring(lt + 1, gt).trim else from.trim

  // --- connector -----------------------------------------------------------

  def services(config: ApolloConfig, hub: SessionHub): List[Unit < (Sync & Async)] =
    fromEnv(config) match
      case Present(cfg) => List(poll(cfg, config, hub))
      case Absent =>
        List(Console.printLine("email: EMAIL_IMAP_HOST/EMAIL_USER/EMAIL_PASSWORD not fully set; not starting"))

  private def poll(cfg: Config, config: ApolloConfig, hub: SessionHub): Unit < (Sync & Async) =
    val interval = config.env.get("EMAIL_POLL_SECONDS").flatMap(p => Maybe.fromOption(p.toIntOption)).getOrElse(30).max(5)
    PlatformEmail.fetchUnseen(cfg).map {
      case Result.Success(msgs) =>
        Kyo.foreachDiscard(msgs)(m => handle(m, cfg, config, hub))
          .andThen(Async.sleep(interval.seconds)).andThen(poll(cfg, config, hub))
      case Result.Failure(e) =>
        Console.printLine(s"email: fetch error (${e.take(200)}); retry in ${interval}s")
          .andThen(Async.sleep(interval.seconds)).andThen(poll(cfg, config, hub))
    }

  private def handle(m: Msg, cfg: Config, config: ApolloConfig, hub: SessionHub): Unit < (Sync & Async) =
    val sender = bareAddress(m.from)
    if m.body.isEmpty then ()
    else if !authorized(config, sender) then
      send(cfg, sender, replySubject(m.subject),
        s"Not authorized. Add $sender to EMAIL_ALLOWED_USERS, or set EMAIL_ALLOW_ALL_USERS=1.")
    else
      val key    = hub.sessionKey("email", "dm", sender, Present(sender))
      val prompt = if m.subject.nonEmpty then s"Subject: ${m.subject}\n\n${m.body}" else m.body
      prompt.trim match
        case "/reset" | "/new" =>
          hub.resetSession(key).andThen(send(cfg, sender, replySubject(m.subject), "Conversation cleared."))
        case _ =>
          Abort.run[Throwable](Abort.catching[Throwable](
            hub.turn(key, "email", prompt).map(reply =>
              send(cfg, sender, replySubject(m.subject), reply.take(maxMessage)))
          )).map {
            case Result.Success(_) => ()
            case _ => send(cfg, sender, replySubject(m.subject), "Something went wrong; please try again.")
          }

  private def authorized(config: ApolloConfig, sender: String): Boolean =
    val env = config.env
    val allowAll = env.getBool("EMAIL_ALLOW_ALL_USERS").getOrElse(false) ||
      env.getBool("GATEWAY_ALLOW_ALL_USERS").getOrElse(false)
    val allowlist =
      (env.get("EMAIL_ALLOWED_USERS").map(_.split(",").toList).getOrElse(Nil)
        ++ env.get("GATEWAY_ALLOWED_USERS").map(_.split(",").toList).getOrElse(Nil)).map(_.trim.toLowerCase).filter(_.nonEmpty)
    allowAll || allowlist.contains(sender.toLowerCase) || allowlist.contains("*")

  private def send(cfg: Config, to: String, subject: String, body: String): Unit < (Sync & Async) =
    PlatformEmail.send(cfg, to, subject, body).map {
      case Result.Success(_) => ()
      case Result.Failure(e) => Console.printLine(s"email: send failed (${e.take(200)})")
    }
end Email

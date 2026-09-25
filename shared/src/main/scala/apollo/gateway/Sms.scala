// SPDX-FileCopyrightText: 2026 David Greco <greco@acm.org>
//
// SPDX-License-Identifier: Apache-2.0

package apollo.gateway

import apollo.config.ApolloConfig
import apollo.http.{HttpError, Transport}
import apollo.util.Crypto
import kyo.*

/** SMS connector via Twilio: inbound texts arrive as `application/x-www-form-
  * urlencoded` webhook POSTs; replies are sent via the Twilio REST API
  * (`POST /Accounts/{sid}/Messages.json`, HTTP Basic auth). HTTP-only, no SDK.
  *
  * The webhook is ACKed immediately with empty TwiML and each message processed
  * on a fiber (an agent turn outlasts Twilio's ~15s webhook window), then the
  * reply is sent out-of-band via REST. `TWILIO_API_BASE` overrides the host
  * (for tests). */
object Sms:

  private val maxMessage = 1500 // SMS segments are short; keep replies reasonable
  final case class Msg(from: String, to: String, body: String)

  def services(config: ApolloConfig, hub: SessionHub): List[Unit < (Sync & Async)] =
    List(serve(config, hub))

  def serve(config: ApolloConfig, hub: SessionHub): Unit < (Sync & Async) =
    val env  = config.env.get
    val port = env("SMS_PORT").flatMap(p => Maybe.fromOption(p.toIntOption)).getOrElse(8646)
    val host = env("SMS_HOST").getOrElse("127.0.0.1")

    val receive = HttpRoute.postText("/sms").handler { req =>
      val form = parseForm(req.fields.body)
      val msg = for
        from <- form.get("From")
        body <- form.get("Body")
      yield Msg(from, form.getOrElse("To", ""), body)
      val work = msg match { case Some(m) => handle(m, config, hub); case None => Sync.defer(()) }
      Fiber.initUnscoped(work).map(_ =>
        HttpResponse.ok("<Response></Response>").setHeader("content-type", "text/xml"))
    }

    Abort.run[Throwable] {
      Scope.run {
        HttpServer.init(port, host)(receive).map { _ =>
          Console.printLine(s"sms: listening on $host:$port/sms")
            .andThen(Async.never)
        }
      }
    }.map {
      case Result.Success(_) => ()
      case Result.Failure(e) => Console.printLine(s"sms server failed: ${e.getMessage}")
      case Result.Panic(e)   => Console.printLine(s"sms server failed: ${String.valueOf(e.getMessage)}")
    }

  // --- pure ----------------------------------------------------------------

  /** Decode an `application/x-www-form-urlencoded` body into a field map. */
  def parseForm(body: String): Map[String, String] =
    body.split("&").iterator.flatMap { pair =>
      pair.split("=", 2) match
        case Array(k, v) if k.nonEmpty => Some(dec(k) -> dec(v))
        case Array(k) if k.nonEmpty    => Some(dec(k) -> "")
        case _                         => None
    }.toMap

  /** Twilio `Messages.json` request body (form-encoded). */
  def sendBody(from: String, to: String, text: String): String =
    List("From" -> from, "To" -> to, "Body" -> text)
      .map((k, v) => s"${enc(k)}=${enc(v)}").mkString("&")

  def basicAuth(sid: String, authToken: String): String =
    "Basic " + Crypto.base64(s"$sid:$authToken".getBytes("UTF-8"))

  private def enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")
  private def dec(s: String): String =
    try java.net.URLDecoder.decode(s, "UTF-8") catch case _: Exception => s

  // --- processing ----------------------------------------------------------

  private[gateway] def handle(m: Msg, config: ApolloConfig, hub: SessionHub): Unit < (Sync & Async) =
    if m.body.isEmpty then ()
    else if !authorized(config, m.from) then
      send(m.from, config, s"Not authorized. Add ${m.from} to SMS_ALLOWED_USERS, or set SMS_ALLOW_ALL_USERS=1.")
    else
      val key = hub.sessionKey("sms", "dm", m.from, Present(m.from))
      m.body.trim match
        case "/reset" | "/new" => hub.resetSession(key).andThen(send(m.from, config, "Conversation cleared."))
        case "/status"         => send(m.from, config, s"session: $key")
        case prompt =>
          Abort.run[Throwable](Abort.catching[Throwable](
            hub.turn(key, "sms", prompt).map(reply => sendChunked(m.from, config, reply))
          )).map {
            case Result.Success(_) => ()
            case _ => send(m.from, config, "Something went wrong; please try again.")
          }

  private def authorized(config: ApolloConfig, sender: String): Boolean =
    val env = config.env
    val allowAll = env.getBool("SMS_ALLOW_ALL_USERS").getOrElse(false) ||
      env.getBool("GATEWAY_ALLOW_ALL_USERS").getOrElse(false)
    val allowlist =
      (env.get("SMS_ALLOWED_USERS").map(_.split(",").toList).getOrElse(Nil)
        ++ env.get("GATEWAY_ALLOWED_USERS").map(_.split(",").toList).getOrElse(Nil)).map(_.trim).filter(_.nonEmpty)
    allowAll || allowlist.contains(sender) || allowlist.contains("*")

  private def sendChunked(to: String, config: ApolloConfig, text: String): Unit < (Sync & Async) =
    val chunks = text.grouped(maxMessage).toList match { case Nil => List("(empty response)"); case cs => cs }
    Kyo.foreachDiscard(chunks)(c => send(to, config, c))

  private def send(to: String, config: ApolloConfig, text: String): Unit < (Sync & Async) =
    val env  = config.env.get
    val sid  = env("TWILIO_ACCOUNT_SID").getOrElse("")
    val tok  = env("TWILIO_AUTH_TOKEN").getOrElse("")
    val from = env("TWILIO_FROM_NUMBER").getOrElse("")
    val base = env("TWILIO_API_BASE").getOrElse("https://api.twilio.com").stripSuffix("/")
    if sid.isEmpty || tok.isEmpty || from.isEmpty then
      Console.printLine("sms: TWILIO_ACCOUNT_SID / TWILIO_AUTH_TOKEN / TWILIO_FROM_NUMBER missing; cannot send")
    else
      val url     = s"$base/2010-04-01/Accounts/$sid/Messages.json"
      val headers = List("authorization" -> basicAuth(sid, tok),
        "content-type" -> "application/x-www-form-urlencoded")
      Abort.run[HttpError](Transport.postJson(url, headers, sendBody(from, to, text), timeout = 30.seconds)).map {
        case Result.Success(_) => ()
        case Result.Failure(e) => Console.printLine(s"sms: send failed: ${e.getMessage.take(200)}")
        case Result.Panic(e)   => Console.printLine(s"sms: send failed: ${String.valueOf(e.getMessage)}")
      }
end Sms

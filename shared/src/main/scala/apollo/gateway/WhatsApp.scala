package apollo.gateway

import apollo.config.ApolloConfig
import apollo.provider.{ProviderError, Transport}
import apollo.util.Jx
import apollo.util.Jx.*
import kyo.*
import kyo.Structure.Value

/** WhatsApp Cloud connector (Meta Graph API): inbound messages arrive as
  * webhook POSTs (a GET first verifies the subscription); replies are sent via
  * `POST /{phone_number_id}/messages` with a bearer token. HTTP-only, no SDK —
  * so it fits apollo's dependency-light design like Matrix.
  *
  * The webhook is ACKed immediately and each message processed on a fiber, so
  * an agent turn (which can outlast Meta's webhook timeout) never blocks the
  * ack. `WHATSAPP_API_BASE` overrides the Graph host (for tests).
  */
object WhatsApp:

  private val maxMessage = 4000
  final case class Msg(from: String, body: String, phoneId: String)

  def services(config: ApolloConfig, hub: SessionHub): List[Unit < (Sync & Async)] =
    List(serve(config, hub))

  def serve(config: ApolloConfig, hub: SessionHub): Unit < (Sync & Async) =
    val env         = config.env.get
    val token       = env("WHATSAPP_TOKEN").getOrElse("")
    val verifyToken = env("WHATSAPP_VERIFY_TOKEN").getOrElse("")
    val apiVersion  = env("WHATSAPP_API_VERSION").getOrElse("v21.0")
    val port        = env("WHATSAPP_PORT").flatMap(p => Maybe.fromOption(p.toIntOption)).getOrElse(8645)
    val host        = env("WHATSAPP_HOST").getOrElse("127.0.0.1")

    val verify = HttpRoute.getText("/whatsapp").handler { req =>
      verifyChallenge(
        req.queryAll("hub.mode").headOption.getOrElse(""),
        req.queryAll("hub.verify_token").headOption.getOrElse(""),
        req.queryAll("hub.challenge").headOption.getOrElse(""),
        verifyToken) match
        case Present(ch) => HttpResponse.ok(ch)
        case Absent      => HttpResponse(HttpStatus.Forbidden).addField("body", "verification failed")
    }
    val receive = HttpRoute.postText("/whatsapp").handler { req =>
      val msgs = parseMessages(Jx.parse(req.fields.body).getOrElse(Jx.obj()))
      Fiber.initUnscoped(Kyo.foreachDiscard(msgs)(m => handle(m, token, apiVersion, config, hub)))
        .map(_ => HttpResponse.ok(""))
    }

    Abort.run[Throwable] {
      Scope.run {
        HttpServer.init(port, host)(verify, receive).map { _ =>
          Console.printLine(s"whatsapp: listening on $host:$port/whatsapp")
            .andThen(Async.never)
        }
      }
    }.map {
      case Result.Success(_) => ()
      case Result.Failure(e) => Console.printLine(s"whatsapp server failed: ${e.getMessage}")
      case Result.Panic(e)   => Console.printLine(s"whatsapp server failed: ${String.valueOf(e.getMessage)}")
    }

  // --- pure ----------------------------------------------------------------

  /** The webhook GET verification: echo the challenge only for a subscribe with
    * the configured verify token. */
  def verifyChallenge(mode: String, verifyToken: String, challenge: String, configToken: String): Maybe[String] =
    if mode == "subscribe" && configToken.nonEmpty && verifyToken == configToken then Present(challenge) else Absent

  /** Extract inbound text messages from a Cloud API webhook payload. */
  def parseMessages(json: Value): List[Msg] =
    (json / "entry").asArr.getOrElse(Chunk.empty).toList.flatMap { entry =>
      (entry / "changes").asArr.getOrElse(Chunk.empty).toList.flatMap { change =>
        val value   = change / "value"
        val phoneId = (value / "metadata" / "phone_number_id").asStr.getOrElse("")
        (value / "messages").asArr.getOrElse(Chunk.empty).toList.flatMap { m =>
          if (m / "type").asStr == Present("text") then
            (for
              from <- (m / "from").asStr
              body <- (m / "text" / "body").asStr
            yield Msg(from, body, phoneId)).toList
          else Nil
        }
      }
    }

  def sendBody(to: String, text: String): String =
    Jx.render(Jx.obj(
      "messaging_product" -> Jx.str("whatsapp"),
      "to"                -> Jx.str(to),
      "type"              -> Jx.str("text"),
      "text"              -> Jx.obj("body" -> Jx.str(text))))

  // --- processing ----------------------------------------------------------

  private[gateway] def handle(
      m: Msg, token: String, apiVersion: String, config: ApolloConfig, hub: SessionHub
  ): Unit < (Sync & Async) =
    if m.body.isEmpty then ()
    else if !authorized(config, m.from) then
      send(m.from, m.phoneId, token, apiVersion, config,
        s"Not authorized. Add ${m.from} to WHATSAPP_ALLOWED_USERS, or set WHATSAPP_ALLOW_ALL_USERS=1.")
    else
      val key = hub.sessionKey("whatsapp", "dm", m.from, Present(m.from))
      m.body.trim match
        case "/reset" | "/new" =>
          hub.resetSession(key).andThen(send(m.from, m.phoneId, token, apiVersion, config, "Conversation cleared."))
        case "/status" => send(m.from, m.phoneId, token, apiVersion, config, s"session: $key")
        case prompt =>
          Abort.run[Throwable](Abort.catching[Throwable](
            hub.turn(key, "whatsapp", prompt).map(reply =>
              sendChunked(m.from, m.phoneId, token, apiVersion, config, reply))
          )).map {
            case Result.Success(_) => ()
            case _ => send(m.from, m.phoneId, token, apiVersion, config, "Something went wrong; please try again.")
          }

  private def authorized(config: ApolloConfig, sender: String): Boolean =
    val env = config.env
    val allowAll = env.getBool("WHATSAPP_ALLOW_ALL_USERS").getOrElse(false) ||
      env.getBool("GATEWAY_ALLOW_ALL_USERS").getOrElse(false)
    val allowlist =
      (env.get("WHATSAPP_ALLOWED_USERS").map(_.split(",").toList).getOrElse(Nil)
        ++ env.get("GATEWAY_ALLOWED_USERS").map(_.split(",").toList).getOrElse(Nil)).map(_.trim).filter(_.nonEmpty)
    allowAll || allowlist.contains(sender) || allowlist.contains("*")

  private def sendChunked(
      to: String, phoneId: String, token: String, apiVersion: String, config: ApolloConfig, text: String
  ): Unit < (Sync & Async) =
    val chunks = text.grouped(maxMessage).toList match { case Nil => List("(empty response)"); case cs => cs }
    Kyo.foreachDiscard(chunks)(c => send(to, phoneId, token, apiVersion, config, c))

  private def send(
      to: String, phoneId: String, token: String, apiVersion: String, config: ApolloConfig, text: String
  ): Unit < (Sync & Async) =
    val pid  = if phoneId.nonEmpty then phoneId else config.env.get("WHATSAPP_PHONE_ID").getOrElse("")
    val base = config.env.get("WHATSAPP_API_BASE").getOrElse("https://graph.facebook.com").stripSuffix("/")
    if pid.isEmpty || token.isEmpty then
      Console.printLine("whatsapp: WHATSAPP_TOKEN / phone id missing; cannot send")
    else
      val url = s"$base/$apiVersion/$pid/messages"
      Abort.run[ProviderError](
        Transport.postJson(url, List("authorization" -> s"Bearer $token"), sendBody(to, text), timeout = 30.seconds)
      ).map {
        case Result.Success(_) => ()
        case Result.Failure(e) => Console.printLine(s"whatsapp: send failed: ${e.getMessage.take(200)}")
        case Result.Panic(e)   => Console.printLine(s"whatsapp: send failed: ${String.valueOf(e.getMessage)}")
      }
end WhatsApp

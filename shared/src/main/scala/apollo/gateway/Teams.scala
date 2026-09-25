// SPDX-FileCopyrightText: 2026 David Greco <greco@acm.org>
//
// SPDX-License-Identifier: Apache-2.0

package apollo.gateway

import apollo.config.ApolloConfig
import apollo.http.{HttpError, Transport}
import apollo.util.Jx
import apollo.util.Jx.*
import kyo.*
import kyo.Structure.Value

/** Microsoft Teams connector via the Bot Framework: inbound activities arrive
  * as webhook POSTs to `/api/messages`; replies POST back to the activity's
  * `serviceUrl` with a bearer minted (and cached) from Azure AD
  * client-credentials. HTTP-only, no SDK.
  *
  * The webhook is ACKed immediately and each activity processed on a fiber
  * (agent turns outlast the Bot Framework's timeout). `TEAMS_LOGIN_BASE`
  * overrides the token host (for tests). */
object Teams:

  private val maxMessage = 12000
  final case class Activity(text: String, fromId: String, fromName: String,
                            conversationId: String, activityId: String, serviceUrl: String)

  // token cache: (bearer, expiresAtMillis)
  private val token = new java.util.concurrent.atomic.AtomicReference[Maybe[(String, Long)]](Absent)

  /** Clears the cached bearer (test hook). */
  def resetState(): Unit = token.set(Absent)

  def services(config: ApolloConfig, hub: SessionHub): List[Unit < (Sync & Async)] =
    List(serve(config, hub))

  def serve(config: ApolloConfig, hub: SessionHub): Unit < (Sync & Async) =
    val env  = config.env.get
    val port = env("TEAMS_PORT").flatMap(p => Maybe.fromOption(p.toIntOption)).getOrElse(8647)
    val host = env("TEAMS_HOST").getOrElse("127.0.0.1")

    val messages = HttpRoute.postText("api" / "messages").handler { req =>
      val work = parseActivity(Jx.parse(req.fields.body).getOrElse(Jx.obj())) match
        case Some(a) => handle(a, config, hub)
        case None    => Sync.defer(())
      Fiber.initUnscoped(work).map(_ => HttpResponse.ok(""))
    }

    Abort.run[Throwable] {
      Scope.run {
        HttpServer.init(port, host)(messages).map { _ =>
          Console.printLine(s"teams: listening on $host:$port/api/messages")
            .andThen(Async.never)
        }
      }
    }.map {
      case Result.Success(_) => ()
      case Result.Failure(e) => Console.printLine(s"teams server failed: ${e.getMessage}")
      case Result.Panic(e)   => Console.printLine(s"teams server failed: ${String.valueOf(e.getMessage)}")
    }

  // --- pure ----------------------------------------------------------------

  /** A message activity from a Bot Framework payload (ignores non-message
    * activity types like typing/conversationUpdate). */
  def parseActivity(json: Value): Option[Activity] =
    if (json / "type").asStr != Present("message") then None
    else
      ((json / "conversation" / "id").asStr, (json / "serviceUrl").asStr) match
        case (Present(conv), Present(svc)) =>
          Some(Activity(
            text = (json / "text").asStr.getOrElse(""),
            fromId = (json / "from" / "id").asStr.getOrElse(""),
            fromName = (json / "from" / "name").asStr.getOrElse(""),
            conversationId = conv,
            activityId = (json / "id").asStr.getOrElse(""),
            serviceUrl = svc))
        case _ => None

  def replyBody(text: String): String =
    Jx.render(Jx.obj("type" -> Jx.str("message"), "text" -> Jx.str(text)))

  def tokenForm(appId: String, password: String): String =
    val enc = (s: String) => java.net.URLEncoder.encode(s, "UTF-8")
    List(
      "grant_type"    -> "client_credentials",
      "client_id"     -> appId,
      "client_secret" -> password,
      "scope"         -> "https://api.botframework.com/.default"
    ).map((k, v) => s"${enc(k)}=${enc(v)}").mkString("&")

  def parseToken(json: Value, nowMillis: Long): Result[String, (String, Long)] =
    (json / "access_token").asStr match
      case Present(t) =>
        val exp = (json / "expires_in").asLong.map(s => nowMillis + s * 1000).getOrElse(nowMillis + 3_600_000)
        Result.succeed((t, exp))
      case Absent => Result.fail((json / "error_description").asStr.getOrElse("no access_token in token response"))

  // --- processing ----------------------------------------------------------

  private[gateway] def handle(a: Activity, config: ApolloConfig, hub: SessionHub): Unit < (Sync & Async) =
    if a.text.isEmpty then ()
    else if !authorized(config, a.fromId) then
      send(a, config, s"Not authorized. Add ${a.fromId} to TEAMS_ALLOWED_USERS, or set TEAMS_ALLOW_ALL_USERS=1.")
    else
      val key = hub.sessionKey("teams", "channel", a.conversationId, Present(a.fromId))
      a.text.trim match
        case "/reset" | "/new" => hub.resetSession(key).andThen(send(a, config, "Conversation cleared."))
        case "/status"         => send(a, config, s"session: $key")
        case prompt =>
          Abort.run[Throwable](Abort.catching[Throwable](
            hub.turn(key, "teams", prompt).map(reply => sendChunked(a, config, reply))
          )).map {
            case Result.Success(_) => ()
            case _                 => send(a, config, "Something went wrong; please try again.")
          }

  private def authorized(config: ApolloConfig, sender: String): Boolean =
    val env = config.env
    val allowAll = env.getBool("TEAMS_ALLOW_ALL_USERS").getOrElse(false) ||
      env.getBool("GATEWAY_ALLOW_ALL_USERS").getOrElse(false)
    val allowlist =
      (env.get("TEAMS_ALLOWED_USERS").map(_.split(",").toList).getOrElse(Nil)
        ++ env.get("GATEWAY_ALLOWED_USERS").map(_.split(",").toList).getOrElse(Nil)).map(_.trim).filter(_.nonEmpty)
    allowAll || allowlist.contains(sender) || allowlist.contains("*")

  private def sendChunked(a: Activity, config: ApolloConfig, text: String): Unit < (Sync & Async) =
    val chunks = text.grouped(maxMessage).toList match { case Nil => List("(empty response)"); case cs => cs }
    Kyo.foreachDiscard(chunks)(c => send(a, config, c))

  private def send(a: Activity, config: ApolloConfig, text: String): Unit < (Sync & Async) =
    bearer(config).map {
      case Result.Failure(e) => Console.printLine(s"teams: auth failed: ${e.take(200)}")
      case Result.Panic(e)   => Console.printLine(s"teams: auth failed: ${String.valueOf(e.getMessage)}")
      case Result.Success(tok) =>
        val url = s"${a.serviceUrl.stripSuffix("/")}/v3/conversations/${a.conversationId}/activities/${a.activityId}"
        Abort.run[HttpError](Transport.postJson(url, List("authorization" -> s"Bearer $tok"), replyBody(text), 30.seconds)).map {
          case Result.Success(_) => ()
          case Result.Failure(e) => Console.printLine(s"teams: send failed: ${e.getMessage.take(200)}")
          case Result.Panic(e)   => Console.printLine(s"teams: send failed: ${String.valueOf(e.getMessage)}")
        }
    }

  /** A valid (cached) Bot Framework bearer, minting a fresh one when missing or
    * within 60s of expiry. */
  private def bearer(config: ApolloConfig): Result[String, String] < (Sync & Async) =
    Sync.defer(java.lang.System.currentTimeMillis()).map { now =>
      token.get match
        case Present((t, exp)) if now < exp - 60_000 => Result.succeed(t)
        case _                                       => mintToken(config, now)
    }

  private def mintToken(config: ApolloConfig, now: Long): Result[String, String] < (Sync & Async) =
    val env   = config.env.get
    val appId = env("TEAMS_APP_ID").getOrElse("")
    val pass  = env("TEAMS_APP_PASSWORD").getOrElse("")
    val base  = env("TEAMS_LOGIN_BASE").getOrElse("https://login.microsoftonline.com").stripSuffix("/")
    val url   = s"$base/botframework.com/oauth2/v2.0/token"
    if appId.isEmpty || pass.isEmpty then Result.fail("TEAMS_APP_ID / TEAMS_APP_PASSWORD not set")
    else
      val headers = List("content-type" -> "application/x-www-form-urlencoded")
      Abort.run[HttpError](Transport.postJson(url, headers, tokenForm(appId, pass), 30.seconds)).map {
        case Result.Success(body) =>
          parseToken(Jx.parse(body).getOrElse(Jx.obj()), now) match
            case Result.Success((t, exp)) => token.set(Present((t, exp))); Result.succeed(t)
            case Result.Failure(e)        => Result.fail(e)
            case _                        => Result.fail("unparseable token response")
        case Result.Failure(e) => Result.fail(s"token request failed: ${e.getMessage}")
        case Result.Panic(e)   => Result.fail(s"token request failed: ${String.valueOf(e.getMessage)}")
      }
end Teams

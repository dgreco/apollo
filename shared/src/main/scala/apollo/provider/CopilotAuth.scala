package apollo.provider

import apollo.config.{ApolloPaths, Fs}
import apollo.util.Jx
import apollo.util.Jx.*
import kyo.*
import kyo.Structure.Value

/** GitHub Copilot authentication: the OAuth device-code flow to obtain a GitHub
  * token, and the Copilot token-exchange that mints the short-lived bearer used
  * against `api.githubcopilot.com`.
  *
  * The endpoints/client-id/headers are the well-known reverse-engineered Copilot
  * values; the pure parsers and poll classification are unit-tested, but the live
  * HTTP flow can only be validated against the real API (`apollo auth copilot
  * login`). If GitHub changes the flow, adjust the constants here.
  */
object CopilotAuth:

  final case class Token(token: String, expiresAt: Long)
  final case class DeviceCode(deviceCode: String, userCode: String, verificationUri: String,
                              interval: Int, expiresIn: Int)
  enum Poll:
    case Pending
    case SlowDown
    case Success(githubToken: String)
    case Error(message: String)

  val clientId         = "Iv1.b507a08c87ecfe98"
  val deviceCodeUrl    = "https://github.com/login/device/code"
  val accessTokenUrl   = "https://github.com/login/oauth/access_token"
  val tokenExchangeUrl = "https://api.github.com/copilot_internal/v2/token"
  val scope            = "read:user"

  /** Headers Copilot expects on both the exchange and the chat requests. */
  val headers: Map[String, String] = Map(
    "Editor-Version"         -> s"apollo/${apolloVersion}",
    "Editor-Plugin-Version"  -> s"apollo/${apolloVersion}",
    "Copilot-Integration-Id" -> "vscode-chat",
    "User-Agent"             -> s"GitHubCopilot/apollo-${apolloVersion}"
  )
  private def apolloVersion = "0.1.0"

  // --- pure parsers ---------------------------------------------------------

  def parseExchange(json: Value): Result[String, Token] =
    (json / "token").asStr match
      case Present(t) => Result.succeed(Token(t, (json / "expires_at").asLong.getOrElse(0L)))
      case Absent     => Result.fail((json / "message").asStr.getOrElse("copilot token-exchange returned no token"))

  def parseDeviceCode(json: Value): Result[String, DeviceCode] =
    (for
      dc <- (json / "device_code").asStr
      uc <- (json / "user_code").asStr
      vu <- (json / "verification_uri").asStr
    yield DeviceCode(dc, uc, vu,
      (json / "interval").asLong.map(_.toInt).getOrElse(5),
      (json / "expires_in").asLong.map(_.toInt).getOrElse(900))) match
      case Present(d) => Result.succeed(d)
      case Absent     => Result.fail("malformed device-code response")

  def classifyPoll(json: Value): Poll =
    (json / "access_token").asStr match
      case Present(t) => Poll.Success(t)
      case Absent =>
        (json / "error").asStr.getOrElse("") match
          case "authorization_pending" => Poll.Pending
          case "slow_down"             => Poll.SlowDown
          case ""                      => Poll.Error("unknown poll error")
          case other                   => Poll.Error(other)

  // --- token storage (the long-lived GitHub token) --------------------------

  private def tokenFile(paths: ApolloPaths) = paths.home.resolve("copilot").resolve("github-token")

  def saveGithubToken(paths: ApolloPaths, token: String): Unit < Sync =
    Fs.createDirs(tokenFile(paths).getParent).andThen(Fs.writeString(tokenFile(paths), token.trim))

  def loadGithubToken(paths: ApolloPaths): Maybe[String] < Sync =
    Fs.readString(tokenFile(paths)).map(_.map(_.trim).filter(_.nonEmpty))

  def logout(paths: ApolloPaths): Unit < Sync = Fs.delete(tokenFile(paths))

  // --- HTTP flow (validated live, not in CI) --------------------------------

  /** Exchange a GitHub token for a short-lived Copilot bearer. */
  def exchange(githubToken: String): Result[String, Token] < (Sync & Async) =
    val hdrs = ("authorization" -> s"token $githubToken") :: ("accept" -> "application/json") :: headers.toList
    Abort.run[ProviderError](Transport.getJson(tokenExchangeUrl, hdrs)).map {
      case Result.Success(body) =>
        Jx.parse(body) match
          case Result.Success(j) => parseExchange(j)
          case _                 => Result.fail("unparseable copilot exchange response")
      case Result.Failure(e) => Result.fail(s"copilot token-exchange failed: ${e.getMessage}")
      case Result.Panic(e)   => Result.fail(s"copilot token-exchange failed: ${String.valueOf(e.getMessage)}")
    }

  def deviceStart(): Result[String, DeviceCode] < (Sync & Async) =
    val body = Jx.render(Jx.obj("client_id" -> Jx.str(clientId), "scope" -> Jx.str(scope)))
    Abort.run[ProviderError](Transport.postJson(deviceCodeUrl, List("accept" -> "application/json"), body)).map {
      case Result.Success(b) =>
        Jx.parse(b) match
          case Result.Success(j) => parseDeviceCode(j)
          case _                 => Result.fail("unparseable device-code response")
      case Result.Failure(e) => Result.fail(s"device-code request failed: ${e.getMessage}")
      case Result.Panic(e)   => Result.fail(s"device-code request failed: ${String.valueOf(e.getMessage)}")
    }

  def pollOnce(deviceCode: String): Poll < (Sync & Async) =
    val body = Jx.render(Jx.obj(
      "client_id"   -> Jx.str(clientId),
      "device_code" -> Jx.str(deviceCode),
      "grant_type"  -> Jx.str("urn:ietf:params:oauth:grant-type:device_code")))
    Abort.run[ProviderError](Transport.postJson(accessTokenUrl, List("accept" -> "application/json"), body)).map {
      case Result.Success(b) =>
        Jx.parse(b) match
          case Result.Success(j) => classifyPoll(j)
          case _                 => Poll.Error("unparseable poll response")
      case Result.Failure(e) => Poll.Error(s"poll failed: ${e.getMessage}")
      case Result.Panic(e)   => Poll.Error(s"poll failed: ${String.valueOf(e.getMessage)}")
    }
end CopilotAuth

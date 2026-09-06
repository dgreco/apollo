package apollo.provider

import apollo.config.{ApolloPaths, Fs}
import apollo.util.{Crypto, Jx}
import apollo.util.Jx.*
import kyo.*
import kyo.Structure.Value

/** Qwen (Alibaba) OAuth 2.0 Device Authorization flow with PKCE — the same
  * login the open-source `qwen-code` CLI uses to reach the Qwen portal's
  * OpenAI-compatible endpoint. `apollo auth qwen login` runs the device flow;
  * `resolveQwen` then uses the stored (auto-refreshed) access token.
  *
  * Endpoints / client-id are the reverse-engineered qwen-code values (like
  * `CopilotAuth`): the pure parsers, PKCE, and poll classification are
  * unit-tested, but the live HTTP flow can only be validated against the real
  * API. If Qwen changes the flow, adjust the constants here.
  */
object QwenAuth:

  final case class Token(
      accessToken: String,
      refreshToken: Maybe[String],
      expiresAt: Long,          // epoch millis; 0 = unknown
      resourceUrl: Maybe[String] // the OpenAI-compatible API base to use
  )
  final case class DeviceCode(
      deviceCode: String, userCode: String, verificationUri: String,
      verificationUriComplete: Maybe[String], interval: Int, expiresIn: Int,
      verifier: String // PKCE code_verifier, needed to poll
  )
  enum Poll:
    case Pending
    case SlowDown
    case Success(token: Token)
    case Error(message: String)

  val clientId      = "f0304373b74a44d2b584a3fb70ca9e56"
  val deviceCodeUrl = "https://chat.qwen.ai/api/v1/oauth2/device/code"
  val tokenUrl      = "https://chat.qwen.ai/api/v1/oauth2/token"
  val scope         = "openid profile email model.completion"
  val grantType     = "urn:ietf:params:oauth:grant-type:device_code"
  val fallbackApiBase = "https://portal.qwen.ai/v1"

  // --- pure -----------------------------------------------------------------

  def parseDeviceCode(json: Value, verifier: String): Result[String, DeviceCode] =
    (for
      dc <- (json / "device_code").asStr
      uc <- (json / "user_code").asStr
      vu <- (json / "verification_uri").asStr
    yield DeviceCode(dc, uc, vu, (json / "verification_uri_complete").asStr,
      (json / "interval").asLong.map(_.toInt).getOrElse(5),
      (json / "expires_in").asLong.map(_.toInt).getOrElse(300), verifier)) match
      case Present(d) => Result.succeed(d)
      case Absent     => Result.fail("malformed device-code response")

  def parseTokenBundle(json: Value, nowMillis: Long): Result[String, Token] =
    (json / "access_token").asStr match
      case Absent => Result.fail((json / "error_description").asStr
        .orElse((json / "error").asStr).getOrElse("token response missing access_token"))
      case Present(at) =>
        val expiresAt = (json / "expires_in").asLong match
          case Present(secs) => nowMillis + secs * 1000
          case Absent        => 0L
        Result.succeed(Token(at, (json / "refresh_token").asStr, expiresAt, (json / "resource_url").asStr))

  def classifyPoll(json: Value, nowMillis: Long): Poll =
    (json / "access_token").asStr match
      case Present(_) => parseTokenBundle(json, nowMillis) match
        case Result.Success(t) => Poll.Success(t)
        case Result.Failure(e) => Poll.Error(e)
        case _                 => Poll.Error("unparseable token response")
      case Absent =>
        (json / "error").asStr.getOrElse("") match
          case "authorization_pending" => Poll.Pending
          case "slow_down"             => Poll.SlowDown
          case ""                      => Poll.Error("unknown poll error")
          case other                   => Poll.Error((json / "error_description").asStr.getOrElse(other))

  /** The Qwen `resource_url` is often a bare host; normalize to a full
    * OpenAI-compatible base ending in `/v1`. */
  def normalizeApiBase(resourceUrl: String): String =
    val withScheme = if resourceUrl.startsWith("http") then resourceUrl else s"https://$resourceUrl"
    val trimmed    = withScheme.stripSuffix("/")
    if trimmed.endsWith("/v1") then trimmed else s"$trimmed/v1"

  def apiBase(token: Token): String =
    token.resourceUrl.filter(_.nonEmpty).map(normalizeApiBase).getOrElse(fallbackApiBase)

  /** Expired (with a 60s safety margin); tokens with unknown expiry are treated
    * as live (the server's 401 then drives a refresh/re-login). */
  def isExpired(token: Token, nowMillis: Long): Boolean =
    token.expiresAt != 0L && nowMillis >= token.expiresAt - 60_000

  // --- token storage --------------------------------------------------------

  private def tokenFile(paths: ApolloPaths) = paths.home.resolve("qwen").resolve("oauth.json")

  def save(paths: ApolloPaths, t: Token): Unit < Sync =
    val json = Jx.render(Jx.objOf(
      "access_token"  -> Present(Jx.str(t.accessToken)),
      "refresh_token" -> t.refreshToken.map(Jx.str),
      "expires_at"    -> Present(Jx.num(t.expiresAt)),
      "resource_url"  -> t.resourceUrl.map(Jx.str)))
    Fs.createDirs(tokenFile(paths).getParent).andThen(Fs.writeString(tokenFile(paths), json))

  def load(paths: ApolloPaths): Maybe[Token] < Sync =
    Fs.readString(tokenFile(paths)).map {
      case Present(s) if s.trim.nonEmpty =>
        Jx.parse(s) match
          case Result.Success(j) =>
            (j / "access_token").asStr.map(at =>
              Token(at, (j / "refresh_token").asStr,
                (j / "expires_at").asLong.getOrElse(0L), (j / "resource_url").asStr))
          case _ => Absent
      case _ => Absent
    }

  def logout(paths: ApolloPaths): Unit < Sync = Fs.delete(tokenFile(paths))

  /** A usable token for resolve time: the stored one if still live, else a
    * refreshed-and-saved one (carrying over resource_url / refresh_token the
    * refresh response may omit), else the stored token (let the server's 401
    * drive a re-login), or Absent when nothing is stored. */
  def ensureFresh(paths: ApolloPaths): Maybe[Token] < (Sync & Async) =
    load(paths).map { stored =>
      val out: Maybe[Token] < (Sync & Async) = stored match
        case Absent => Absent
        case Present(tok) =>
          Sync.defer(java.lang.System.currentTimeMillis()).map { now =>
            val r: Maybe[Token] < (Sync & Async) =
              if !isExpired(tok, now) then Present(tok)
              else tok.refreshToken match
                case Absent => Present(tok)
                case Present(rt) =>
                  refresh(rt).map { res =>
                    val nested: Maybe[Token] < (Sync & Async) = res match
                      case Result.Success(nt0) =>
                        val nt = nt0.copy(
                          resourceUrl  = if nt0.resourceUrl.nonEmpty then nt0.resourceUrl else tok.resourceUrl,
                          refreshToken = if nt0.refreshToken.nonEmpty then nt0.refreshToken else Present(rt))
                        save(paths, nt).andThen(Present(nt))
                      case _ => Present(tok)
                    nested
                  }
            r
          }
      out
    }

  // --- HTTP flow (validated live, not in CI) --------------------------------

  private def form(pairs: (String, String)*): String =
    pairs.map((k, v) => s"${enc(k)}=${enc(v)}").mkString("&")
  private def enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")
  private val formHeader = List("content-type" -> "application/x-www-form-urlencoded", "accept" -> "application/json")

  def deviceStart(): Result[String, DeviceCode] < (Sync & Async) =
    Sync.defer(Crypto.randomToken(32)).map { verifier =>
      val challenge = Crypto.pkceChallenge(verifier)
      val body = form("client_id" -> clientId, "scope" -> scope,
        "code_challenge" -> challenge, "code_challenge_method" -> "S256")
      Abort.run[ProviderError](Transport.postJson(deviceCodeUrl, formHeader, body)).map {
        case Result.Success(b) =>
          Jx.parse(b) match
            case Result.Success(j) => parseDeviceCode(j, verifier)
            case _                 => Result.fail("unparseable device-code response")
        case Result.Failure(e) => Result.fail(s"device-code request failed: ${e.getMessage}")
        case Result.Panic(e)   => Result.fail(s"device-code request failed: ${String.valueOf(e.getMessage)}")
      }
    }

  def pollOnce(deviceCode: String, verifier: String): Poll < (Sync & Async) =
    Sync.defer(java.lang.System.currentTimeMillis()).map { now =>
      val body = form("grant_type" -> grantType, "client_id" -> clientId,
        "device_code" -> deviceCode, "code_verifier" -> verifier)
      Abort.run[ProviderError](Transport.postJson(tokenUrl, formHeader, body)).map {
        case Result.Success(b) =>
          Jx.parse(b) match
            case Result.Success(j) => classifyPoll(j, now)
            case _                 => Poll.Error("unparseable poll response")
        case Result.Failure(e) => Poll.Error(s"poll failed: ${e.getMessage}")
        case Result.Panic(e)   => Poll.Error(s"poll failed: ${String.valueOf(e.getMessage)}")
      }
    }

  /** Refresh an expired access token. */
  def refresh(refreshToken: String): Result[String, Token] < (Sync & Async) =
    Sync.defer(java.lang.System.currentTimeMillis()).map { now =>
      val body = form("grant_type" -> "refresh_token", "client_id" -> clientId,
        "refresh_token" -> refreshToken)
      Abort.run[ProviderError](Transport.postJson(tokenUrl, formHeader, body)).map {
        case Result.Success(b) =>
          Jx.parse(b) match
            case Result.Success(j) => parseTokenBundle(j, now)
            case _                 => Result.fail("unparseable refresh response")
        case Result.Failure(e) => Result.fail(s"qwen token refresh failed: ${e.getMessage}")
        case Result.Panic(e)   => Result.fail(s"qwen token refresh failed: ${String.valueOf(e.getMessage)}")
      }
    }
end QwenAuth

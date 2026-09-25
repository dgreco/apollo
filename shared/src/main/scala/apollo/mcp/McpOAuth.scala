// SPDX-FileCopyrightText: 2026 David Greco <greco@acm.org>
//
// SPDX-License-Identifier: Apache-2.0

package apollo.mcp

import apollo.http.{HttpError, Transport}
import apollo.util.{Crypto, Jx}
import apollo.util.Jx.*
import kyo.*
import kyo.Structure.Value

/** How the OAuth flow asks the operator to paste the redirect URL when the
  * loopback callback can't be reached (remote shell, locked-down browser).
  *
  * Owned by this package rather than taken as a terminal line-editor: the flow
  * needs exactly one capability — read one line, `Absent` on EOF — and
  * inverting it keeps the MCP subsystem below the CLI that binds it.
  */
trait CodePrompt:
  def readLine(prompt: String): Maybe[String] < (Sync & Async)

/** OAuth 2.1 + PKCE for remote (streamable-HTTP) MCP servers. Hermes
  * delegates the protocol wire to the Python MCP SDK; here it is implemented
  * directly against the relevant RFCs — RFC 9728 protected-resource metadata,
  * RFC 8414 authorization-server metadata, RFC 7636 PKCE (S256), RFC 7591
  * dynamic client registration, RFC 8707 resource indicators — with the
  * loopback-redirect browser flow and stdin paste fallback the user chose.
  *
  * Interactive authorization runs only from `apollo mcp login`; connect-time
  * (startup/gateway) uses cached tokens and silent refresh, never a browser.
  * Out of scope (documented): CIMD, dashboard flows, provider-specific
  * quirks, and the poison/backup registration dance.
  */
object McpOAuth:

  /** Refresh this far ahead of expiry. */
  private val refreshSkewMillis = 60_000L
  private val callbackPath      = "/callback"
  private val redirectHost      = "127.0.0.1"

  /** Test seam: when set, receives the built authorization URL instead of
    * the print-and-open path, so a test can play the browser (parse `state`,
    * hit the loopback callback). Production leaves this Absent.
    */
  private[mcp] var authUrlHook: Maybe[String => (Unit < (Sync & Async))] = Absent

  // --- discovery (RFC 9728 → RFC 8414) ------------------------------------

  /** Resolves the authorization-server endpoints for `serverUrl`. Tries the
    * protected-resource metadata first (following `authorization_servers`),
    * then the authorization-server / OpenID metadata, with well-known paths
    * derived from the server origin. `resourceMetadataUrl`, when supplied
    * (parsed from a 401 `WWW-Authenticate`), is tried before the derived
    * PRM URLs.
    */
  def discover(
      serverUrl: String,
      timeout: Duration,
      resourceMetadataUrl: Maybe[String] = Absent
  ): Result[String, OAuthEndpoints] < (Sync & Async) =
    val origin = originOf(serverUrl)
    val prmUrls = resourceMetadataUrl.toList ++ List(
      s"$origin/.well-known/oauth-protected-resource",
      s"$origin/.well-known/oauth-protected-resource${pathOf(serverUrl)}"
    )
    firstMetadata(prmUrls, timeout).map { prm =>
      val authServer = prm.flatMap(m => Maybe.fromOption(
        (m / "authorization_servers").asArr.getOrElse(Chunk.empty).headOption)
        .flatMap(_.asStr)).getOrElse(origin)
      val asmOrigin = originOf(authServer)
      val asmUrls = List(
        s"$asmOrigin/.well-known/oauth-authorization-server",
        s"$asmOrigin/.well-known/openid-configuration",
        s"$authServer/.well-known/oauth-authorization-server"
      ).distinct
      firstMetadata(asmUrls, timeout).map {
        case Absent => Result.fail(s"no OAuth authorization-server metadata found for $serverUrl")
        case Present(asm) =>
          (for
            auth  <- (asm / "authorization_endpoint").asStr
            token <- (asm / "token_endpoint").asStr
          yield OAuthEndpoints(auth, token, (asm / "registration_endpoint").asStr)) match
            case Present(e) => Result.succeed(e)
            case Absent     => Result.fail(s"authorization-server metadata for $serverUrl lacks endpoints")
      }
    }

  private def firstMetadata(urls: List[String], timeout: Duration): Maybe[Value] < (Sync & Async) =
    urls match
      case Nil => Absent
      case url :: rest =>
        Abort.run[HttpError](Transport.getJson(url, Nil, timeout)).map {
          case Result.Success(body) =>
            Jx.parse(body) match
              case Result.Success(v) if v.asObj.nonEmpty => Present(v)
              case _                                     => firstMetadata(rest, timeout)
          case _ => firstMetadata(rest, timeout)
        }

  /** RFC 9728: the `resource_metadata` URL advertised in a 401
    * `WWW-Authenticate: Bearer ... resource_metadata="..."` header.
    */
  def parseResourceMetadata(wwwAuthenticate: String): Maybe[String] =
    Maybe.fromOption("resource_metadata=\"([^\"]+)\"".r.findFirstMatchIn(wwwAuthenticate).map(_.group(1)))

  // --- dynamic client registration (RFC 7591) -----------------------------

  /** Returns the client to use: a config-supplied static client, a cached
    * registration, or a fresh dynamic registration against
    * `registration_endpoint`.
    */
  def ensureClient(
      cfg: McpServerConfig,
      endpoints: OAuthEndpoints,
      redirectUri: String,
      store: McpOAuthStore,
      timeout: Duration
  ): Result[String, OAuthClient] < (Sync & Async) =
    cfg.oauthClientId match
      case Present(id) =>
        val method = if cfg.oauthClientSecret.nonEmpty then "client_secret_post" else "none"
        val client = OAuthClient(id, cfg.oauthClientSecret, method)
        store.saveClient(cfg.name, client).andThen(Result.succeed(client))
      case Absent =>
        store.loadClient(cfg.name).map {
          case Present(cached) => Result.succeed(cached)
          case Absent =>
            endpoints.registrationEndpoint match
              case Absent =>
                Result.fail(s"MCP server '${cfg.name}' needs OAuth but advertises no registration " +
                  "endpoint; set oauth.client_id / oauth.client_secret in config.")
              case Present(regEndpoint) =>
                register(cfg, regEndpoint, redirectUri, store, timeout)
        }

  private def register(
      cfg: McpServerConfig,
      regEndpoint: String,
      redirectUri: String,
      store: McpOAuthStore,
      timeout: Duration
  ): Result[String, OAuthClient] < (Sync & Async) =
    val body = Jx.render(Jx.objOf(
      "client_name"                -> Present(Jx.str("apollo")),
      "redirect_uris"              -> Present(Jx.arr(Jx.str(redirectUri))),
      "grant_types"                -> Present(Jx.arr(Jx.str("authorization_code"), Jx.str("refresh_token"))),
      "response_types"             -> Present(Jx.arr(Jx.str("code"))),
      "token_endpoint_auth_method" -> Present(Jx.str("none")),
      "application_type"           -> Present(Jx.str("native")),
      "scope"                      -> (if cfg.oauthScopes.nonEmpty then Present(Jx.str(cfg.oauthScopes.mkString(" "))) else Absent)
    ))
    Abort.run[HttpError](Transport.postJson(regEndpoint, Nil, body, timeout)).map {
      case Result.Success(text) =>
        Jx.parse(text) match
          case Result.Success(v) =>
            (v / "client_id").asStr match
              case Present(id) =>
                val secret = (v / "client_secret").asStr
                val method = (v / "token_endpoint_auth_method").asStr
                  .getOrElse(if secret.nonEmpty then "client_secret_post" else "none")
                val client = OAuthClient(id, secret, method)
                store.saveClient(cfg.name, client).andThen(Result.succeed(client))
              case Absent => Result.fail(s"dynamic client registration for '${cfg.name}' returned no client_id")
          case _ => Result.fail(s"dynamic client registration for '${cfg.name}' returned invalid JSON")
      case Result.Failure(e) => Result.fail(s"dynamic client registration failed: ${e.getMessage}")
      case Result.Panic(e)   => Result.fail(s"dynamic client registration failed: ${String.valueOf(e.getMessage)}")
    }

  // --- authorization URL + token exchange ---------------------------------

  private def form(pairs: (String, String)*): String =
    pairs.map((k, v) => s"$k=${enc(v)}").mkString("&")

  private def enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

  def buildAuthUrl(
      endpoints: OAuthEndpoints,
      client: OAuthClient,
      redirectUri: String,
      serverUrl: String,
      scopes: List[String],
      state: String,
      codeChallenge: String
  ): String =
    val base = if endpoints.authorizationEndpoint.contains("?") then "&" else "?"
    endpoints.authorizationEndpoint + base + form(
      Seq(
        "response_type"         -> "code",
        "client_id"             -> client.clientId,
        "redirect_uri"          -> redirectUri,
        "state"                 -> state,
        "code_challenge"        -> codeChallenge,
        "code_challenge_method" -> "S256",
        "resource"              -> serverUrl // RFC 8707
      ) ++ (if scopes.nonEmpty then Seq("scope" -> scopes.mkString(" ")) else Nil)*
    )

  private val formHeader = List("content-type" -> "application/x-www-form-urlencoded")

  def exchangeCode(
      endpoints: OAuthEndpoints,
      client: OAuthClient,
      code: String,
      redirectUri: String,
      verifier: String,
      serverUrl: String,
      timeout: Duration
  ): Result[String, OAuthTokens] < (Sync & Async) =
    val body = form(
      (Seq(
        "grant_type"    -> "authorization_code",
        "code"          -> code,
        "redirect_uri"  -> redirectUri,
        "client_id"     -> client.clientId,
        "code_verifier" -> verifier,
        "resource"      -> serverUrl
      ) ++ client.clientSecret.map("client_secret" -> _).toList)*
    )
    tokenRequest(endpoints.tokenEndpoint, body, timeout)

  def refresh(
      endpoints: OAuthEndpoints,
      client: OAuthClient,
      refreshToken: String,
      serverUrl: String,
      timeout: Duration
  ): Result[String, OAuthTokens] < (Sync & Async) =
    val body = form(
      (Seq(
        "grant_type"    -> "refresh_token",
        "refresh_token" -> refreshToken,
        "client_id"     -> client.clientId,
        "resource"      -> serverUrl
      ) ++ client.clientSecret.map("client_secret" -> _).toList)*
    )
    tokenRequest(endpoints.tokenEndpoint, body, timeout)

  private def tokenRequest(
      tokenEndpoint: String, body: String, timeout: Duration
  ): Result[String, OAuthTokens] < (Sync & Async) =
    Abort.run[HttpError](Transport.postJson(tokenEndpoint, formHeader, body, timeout)).map {
      case Result.Success(text) =>
        Jx.parse(text) match
          case Result.Success(v) => parseTokens(v)
          case _                 => Result.fail("token endpoint returned invalid JSON")
      case Result.Failure(e) => Result.fail(s"token request failed: ${e.getMessage}")
      case Result.Panic(e)   => Result.fail(s"token request failed: ${String.valueOf(e.getMessage)}")
    }

  private def parseTokens(v: Value): Result[String, OAuthTokens] =
    (v / "access_token").asStr match
      case Absent => Result.fail("token response missing access_token")
      case Present(at) =>
        val expiresIn = (v / "expires_in").asLong
        val expiresAt = expiresIn.map(s => nowMillis() + s * 1000).getOrElse(0L)
        Result.succeed(OAuthTokens(
          accessToken = at,
          refreshToken = (v / "refresh_token").asStr,
          tokenType = (v / "token_type").asStr.getOrElse("Bearer"),
          scope = (v / "scope").asStr,
          expiresAtMillis = expiresAt
        ))

  // --- token source (connect-time bearer + silent refresh) ----------------

  /** A live bearer supplier for one server: returns the cached access token,
    * refreshing it (once, serialized) when it is near expiry or after a 401.
    * Never launches a browser — a missing/unrefreshable token surfaces as a
    * needs-login failure the manager turns into a parked server.
    */
  final class TokenSource(
      cfg: McpServerConfig,
      store: McpOAuthStore,
      timeout: Duration,
      mutex: Meter
  ):
    def serverUrl: String = cfg.url.getOrElse("")

    /** Current access token, refreshing ahead of expiry. */
    def bearer: Result[String, String] < (Sync & Async) =
      Abort.run[Closed](mutex.run(current(force = false))).map {
        case Result.Success(r) => r
        case _                 => Result.fail(s"MCP server '${cfg.name}': token lookup unavailable")
      }

    /** Force one refresh (called after a 401); success means a new token is
      * cached.
      */
    def refreshNow: Result[String, String] < (Sync & Async) =
      Abort.run[Closed](mutex.run(current(force = true))).map {
        case Result.Success(r) => r
        case _                 => Result.fail(s"MCP server '${cfg.name}': token refresh unavailable")
      }

    private def current(force: Boolean): Result[String, String] < (Sync & Async) =
      store.loadTokens(cfg.name).map {
        case Absent =>
          Result.fail(s"MCP server '${cfg.name}' is not authorized. Run `apollo mcp login ${cfg.name}`.")
        case Present(tok) =>
          val stale = force || tok.expiresWithin(refreshSkewMillis, nowMillis())
          if !stale then Result.succeed(tok.accessToken)
          else
            tok.refreshToken match
              case Absent =>
                if force then
                  Result.fail(s"MCP server '${cfg.name}' token expired and has no refresh token. " +
                    s"Run `apollo mcp login ${cfg.name}`.")
                else Result.succeed(tok.accessToken)
              case Present(rt) =>
                doRefresh(rt)
      }

    private def doRefresh(refreshToken: String): Result[String, String] < (Sync & Async) =
      loadContext(cfg, store).map {
        case Result.Failure(err) => Result.fail(err)
        case Result.Success((endpoints, client)) =>
          McpOAuth.refresh(endpoints, client, refreshToken, serverUrl, timeout).map {
            case Result.Success(fresh) =>
              // Some servers omit a new refresh_token; keep the old one.
              val merged = fresh.copy(refreshToken = fresh.refreshToken.orElse(Present(refreshToken)))
              store.saveTokens(cfg.name, merged).andThen(Result.succeed(merged.accessToken))
            case Result.Failure(err) =>
              Result.fail(s"MCP server '${cfg.name}' token refresh failed ($err). " +
                s"Run `apollo mcp login ${cfg.name}`.")
            case Result.Panic(e) => Result.fail(String.valueOf(e.getMessage))
          }
      }
  end TokenSource

  def tokenSource(cfg: McpServerConfig, store: McpOAuthStore, timeout: Duration): TokenSource < Sync =
    Meter.initMutexUnscoped.map(m => new TokenSource(cfg, store, timeout, m))

  /** Loads cached endpoints + client, or fails with a run-login hint. */
  private def loadContext(
      cfg: McpServerConfig, store: McpOAuthStore
  ): Result[String, (OAuthEndpoints, OAuthClient)] < Sync =
    store.loadEndpoints(cfg.name).map {
      case Absent => Result.fail(s"MCP server '${cfg.name}' has no cached OAuth metadata. Run `apollo mcp login ${cfg.name}`.")
      case Present(endpoints) =>
        store.loadClient(cfg.name).map {
          case Absent => Result.fail(s"MCP server '${cfg.name}' has no cached OAuth client. Run `apollo mcp login ${cfg.name}`.")
          case Present(client) => Result.succeed((endpoints, client))
        }
    }

  // --- interactive login (apollo mcp login) -------------------------------

  /** The full browser + loopback flow: discover → ensure client → PKCE →
    * open/print auth URL → capture the redirect on 127.0.0.1 (racing a stdin
    * paste) → exchange for tokens → persist. Returns the granted scope on
    * success.
    */
  def login(
      cfg: McpServerConfig,
      store: McpOAuthStore,
      prompt: CodePrompt,
      timeout: Duration
  ): Result[String, String] < (Sync & Async) =
    cfg.url match
      case Absent => Result.fail(s"MCP server '${cfg.name}' has no url; OAuth applies to remote servers only.")
      case Present(serverUrl) =>
        discover(serverUrl, timeout).map {
          case Result.Failure(err) => Result.fail(err)
          case Result.Success(endpoints) =>
            store.saveEndpoints(cfg.name, endpoints).andThen {
              CallbackServer.start(cfg.oauthRedirectPort).map {
                case Result.Failure(err) => Result.fail(err)
                case Result.Success(server) =>
                  val redirectUri = s"http://$redirectHost:${server.port}$callbackPath"
                  ensureClient(cfg, endpoints, redirectUri, store, timeout).map {
                    case Result.Failure(err) => server.stop.andThen(Result.fail(err))
                    case Result.Success(client) =>
                      val verifier  = Crypto.randomToken(48)
                      val challenge = Crypto.pkceChallenge(verifier)
                      val state     = Crypto.randomToken(24)
                      val authUrl   = buildAuthUrl(endpoints, client, redirectUri, serverUrl,
                        cfg.oauthScopes, state, challenge)
                      val present   = authUrlHook match
                        case Present(hook) => hook(authUrl)
                        case Absent        => announce(authUrl)
                      present.andThen {
                        awaitCode(server, prompt, state).map {
                          case Result.Failure(err) => server.stop.andThen(Result.fail(err))
                          case Result.Success(code) =>
                            server.stop.andThen {
                              exchangeCode(endpoints, client, code, redirectUri, verifier, serverUrl, timeout).map {
                                case Result.Success(tokens) =>
                                  store.saveTokens(cfg.name, tokens)
                                    .andThen(Result.succeed(tokens.scope.getOrElse("(default scope)")))
                                case Result.Failure(err) => Result.fail(err)
                                case Result.Panic(e)     => Result.fail(String.valueOf(e.getMessage))
                              }
                            }
                        }
                      }
                  }
              }
            }
        }

  /** Prints the URL (always) and makes a best-effort, portable attempt to
    * open a browser via the OS opener — no `java.awt` (absent on Native).
    */
  private def announce(url: String): Unit < (Sync & Async) =
    Console.printLine(s"\nAuthorize apollo in your browser:\n\n  $url\n").andThen {
      val os = java.lang.System.getProperty("os.name", "").toLowerCase
      val opener =
        if os.contains("mac") then Command("open", url)
        else if os.contains("win") then Command("cmd", "/c", "start", url)
        else Command("xdg-open", url)
      Abort.run[Any](Abort.catching[Throwable](opener.spawnUnscoped)).unit
    }

  /** Races the loopback callback against a stdin paste; validates `state`. */
  private def awaitCode(
      server: CallbackServer, prompt: CodePrompt, expectedState: String
  ): Result[String, String] < (Sync & Async) =
    val fromServer: Result[String, String] < (Sync & Async) =
      server.await.map(validate(_, expectedState))
    val fromPaste: Result[String, String] < (Sync & Async) =
      prompt.readLine(
        "…or paste the full redirect URL here (or the ?code=… part), or 'skip': "
      ).map {
        case Present(line) if line.trim.toLowerCase == "skip" || line.trim.isEmpty =>
          Result.fail("authorization skipped")
        case Present(line) => validate(parseQuery(line), expectedState)
        case Absent        => Result.fail("no input")
      }
    Async.raceFirst(fromServer, fromPaste)

  private def validate(params: Map[String, String], expectedState: String): Result[String, String] =
    params.get("error") match
      case Some(err) => Result.fail(s"authorization error: $err")
      case None =>
        params.get("code") match
          case None => Result.fail("redirect carried no authorization code")
          case Some(code) =>
            params.get("state") match
              case Some(s) if s != expectedState => Result.fail("state mismatch (possible CSRF); aborting")
              case _                             => Result.succeed(code)

  /** Parses a full redirect URL or a bare/`?`-prefixed query into params. */
  private[mcp] def parseQuery(input: String): Map[String, String] =
    val q = input.trim
    val query = q.indexOf('?') match
      case -1 => q
      case i  => q.substring(i + 1)
    query.split("&").flatMap { pair =>
      pair.split("=", 2) match
        case Array(k, v) if k.nonEmpty => Some(k -> java.net.URLDecoder.decode(v, "UTF-8"))
        case _                         => None
    }.toMap

  // --- helpers -------------------------------------------------------------

  private def nowMillis(): Long = java.lang.System.currentTimeMillis()

  private def originOf(url: String): String =
    try
      val u = java.net.URI.create(url)
      val port = if u.getPort > 0 then s":${u.getPort}" else ""
      s"${u.getScheme}://${u.getHost}$port"
    catch case _: Exception => url

  private def pathOf(url: String): String =
    try
      val p = java.net.URI.create(url).getPath
      if p == null || p.isEmpty || p == "/" then "" else p
    catch case _: Exception => ""
end McpOAuth

package apollo.mcp

import apollo.config.ApolloPaths
import apollo.util.Jx.*
import apollo.util.Jx
import kyo.*

/** Unit coverage for the OAuth pieces plus a full-stack login E2E against an
  * in-process fake authorization server + MCP resource server.
  */
class McpOAuthSuite extends munit.FunSuite:

  private def run[A](v: A < (Sync & Async & Scope)): A =
    import AllowUnsafe.embrace.danger
    KyoApp.Unsafe.runAndBlock(60.seconds)(Scope.run(v)).getOrThrow

  override def afterEach(context: AfterEach): Unit =
    McpOAuth.authUrlHook = Absent

  private def tempPaths(): ApolloPaths =
    ApolloPaths(java.nio.file.Files.createTempDirectory("apollo-oauth-home"))

  // --- unit ----------------------------------------------------------------

  test("parseQuery handles full URL, bare query, and ?-prefixed forms") {
    val expect = Map("code" -> "abc", "state" -> "xyz")
    assertEquals(McpOAuth.parseQuery("http://127.0.0.1:9/callback?code=abc&state=xyz"), expect)
    assertEquals(McpOAuth.parseQuery("code=abc&state=xyz"), expect)
    assertEquals(McpOAuth.parseQuery("?code=abc&state=xyz"), expect)
    assertEquals(McpOAuth.parseQuery("?code=a%20b").get("code"), Some("a b"))
  }

  test("parseResourceMetadata extracts the WWW-Authenticate hint") {
    assertEquals(
      McpOAuth.parseResourceMetadata("""Bearer resource_metadata="https://x/.well-known/oauth-protected-resource""""),
      Present("https://x/.well-known/oauth-protected-resource"))
    assertEquals(McpOAuth.parseResourceMetadata("Bearer"), Absent)
  }

  test("token store round-trips tokens, client, endpoints and honors expiry") {
    val paths = tempPaths()
    val store = new McpOAuthStore(paths)
    run {
      val toks = OAuthTokens("at", Present("rt"), "Bearer", Present("mcp"), 1000L)
      store.saveTokens("srv", toks)
        .andThen(store.saveClient("srv", OAuthClient("cid", Present("sec"), "client_secret_post")))
        .andThen(store.saveEndpoints("srv", OAuthEndpoints("https://a/authorize", "https://a/token", Present("https://a/register"))))
        .andThen {
          for
            t <- store.loadTokens("srv")
            c <- store.loadClient("srv")
            e <- store.loadEndpoints("srv")
          yield
            assertEquals(t.map(_.accessToken), Present("at"))
            assertEquals(t.map(_.refreshToken), Present(Present("rt")))
            assertEquals(c.map(_.clientId), Present("cid"))
            assertEquals(e.map(_.tokenEndpoint), Present("https://a/token"))
        }
    }
    // Expiry math.
    assert(OAuthTokens("a", Absent, "Bearer", Absent, 10_000L).expiresWithin(60_000L, 5_000L))
    assert(!OAuthTokens("a", Absent, "Bearer", Absent, 10_000_000L).expiresWithin(60_000L, 5_000L))
    // logout clears everything.
    run(store.remove("srv").andThen(store.loadTokens("srv")).map(t => assertEquals(t, Absent)))
  }

  test("buildAuthUrl carries PKCE S256, state, and the RFC 8707 resource") {
    val url = McpOAuth.buildAuthUrl(
      OAuthEndpoints("https://a/authorize", "https://a/token", Absent),
      OAuthClient("cid", Absent, "none"),
      "http://127.0.0.1:5000/callback", "https://mcp.example.com/mcp",
      List("mcp:read", "mcp:write"), "STATE123", "CHALLENGE")
    assert(url.startsWith("https://a/authorize?"), url)
    assert(url.contains("response_type=code"), url)
    assert(url.contains("client_id=cid"), url)
    assert(url.contains("code_challenge=CHALLENGE"), url)
    assert(url.contains("code_challenge_method=S256"), url)
    assert(url.contains("state=STATE123"), url)
    assert(url.contains("resource=https%3A%2F%2Fmcp.example.com%2Fmcp"), url)
    assert(url.contains("scope=mcp%3Aread+mcp%3Awrite"), url)
  }

  // --- E2E -----------------------------------------------------------------

  test("login E2E: discover, DCR, PKCE exchange, then an authenticated MCP call") {
    val registered = new java.util.concurrent.atomic.AtomicReference[String]("")
    val issuedCode = "the-auth-code"
    val access     = new java.util.concurrent.atomic.AtomicReference[String]("access-1")
    val seenVerifier = new java.util.concurrent.atomic.AtomicReference[String]("")

    def json(v: kyo.Structure.Value) = HttpResponse.ok(Jx.render(v)).setHeader("content-type", "application/json")

    run {
      Abort.run[HttpBindException] {
        for
          // The authorization + resource server. We bind it first, then use
          // its own port in the metadata it advertises.
          holder <- AtomicRef.init("")
          prm = HttpRoute.getRaw("/.well-known/oauth-protected-resource").response(_.bodyText).handler { _ =>
                  holder.get.map(base => json(Jx.obj("authorization_servers" -> Jx.arr(Jx.str(base)))))
                }
          asm = HttpRoute.getRaw("/.well-known/oauth-authorization-server").response(_.bodyText).handler { _ =>
                  holder.get.map(base => json(Jx.obj(
                    "authorization_endpoint" -> Jx.str(s"$base/authorize"),
                    "token_endpoint"         -> Jx.str(s"$base/token"),
                    "registration_endpoint"  -> Jx.str(s"$base/register"))))
                }
          register = HttpRoute.postText("/register").handler { req =>
                       registered.set(req.fields.body)
                       json(Jx.obj("client_id" -> Jx.str("dcr-client"), "token_endpoint_auth_method" -> Jx.str("none")))
                     }
          token = HttpRoute.postText("/token").handler { req =>
                    val params = McpOAuth.parseQuery("?" + req.fields.body)
                    seenVerifier.set(params.getOrElse("code_verifier", ""))
                    if params.get("code").contains(issuedCode) || params.get("grant_type").contains("refresh_token") then
                      json(Jx.obj(
                        "access_token"  -> Jx.str(access.get),
                        "refresh_token" -> Jx.str("refresh-1"),
                        "token_type"    -> Jx.str("Bearer"),
                        "expires_in"    -> Jx.num(3600L),
                        "scope"         -> Jx.str("mcp:read")))
                    else HttpResponse(HttpStatus.BadRequest).addField("body", """{"error":"invalid_grant"}""")
                  }
          // The MCP resource endpoint: requires the current bearer, else 401.
          mcp = HttpRoute.postRaw("/mcp").request(_.bodyText).response(_.bodyText).handler { req =>
                  val auth = req.headers.get("authorization").getOrElse("")
                  if auth != s"Bearer ${access.get}" then
                    HttpResponse(HttpStatus.Unauthorized).addField("body", "unauthorized")
                  else
                    val msg = Jx.parse(req.fields.body).getOrElse(Jx.obj())
                    val id  = (msg / "id").asLong.getOrElse(0L)
                    val method = (msg / "method").asStr.getOrElse("")
                    val result = method match
                      case "initialize" =>
                        Jx.obj("protocolVersion" -> Jx.str("2025-06-18"),
                          "capabilities" -> Jx.obj("tools" -> Jx.obj()),
                          "serverInfo" -> Jx.obj("name" -> Jx.str("oauth-fake"), "version" -> Jx.str("0")))
                      case "tools/list" =>
                        Jx.obj("tools" -> Jx.arr(Jx.obj("name" -> Jx.str("secret"),
                          "description" -> Jx.str("needs auth"), "inputSchema" -> Jx.obj("type" -> Jx.str("object")))))
                      case _ => Jx.obj()
                    HttpResponse.ok(Jx.render(Jx.obj("jsonrpc" -> Jx.str("2.0"), "id" -> Jx.num(id), "result" -> result)))
                      .setHeader("content-type", "application/json")
                }
          server <- HttpServer.init(0, "127.0.0.1")(prm, asm, register, token, mcp)
          base    = s"http://127.0.0.1:${server.port}"
          _      <- holder.set(base)
        yield (server.port, base)
      }.map {
        case Result.Failure(e) => throw new AssertionError(s"bind failed: $e")
        case Result.Panic(e)   => throw e
        case Result.Success((port, base)) =>
          val paths = tempPaths()
          val store = new McpOAuthStore(paths)
          val cfg = McpServerConfig(
            name = "oauthsrv", enabled = true, command = Absent, args = Nil, env = Map.empty,
            cwd = Absent, url = Present(s"$base/mcp"), headers = Nil, transport = Absent,
            auth = Present("oauth"), connectTimeoutSeconds = 10.0, toolTimeoutSeconds = 10.0,
            keepaliveIntervalSeconds = 3600.0, include = Absent, exclude = Nil,
            resourceTools = false, promptTools = false, untrusted = false,
            oauthClientId = Absent, oauthClientSecret = Absent, oauthScopes = List("mcp:read"),
            oauthRedirectPort = 0)

          // A scripted paste prompt: it blocks so the loopback branch always
          // wins the race; a background fiber plays the browser by GETting the
          // fixed redirect URL with the issued code.
          val paste: apollo.mcp.CodePrompt = _ => Async.sleep(30.seconds).andThen(Absent)

          val fixedCfg = cfg.copy(oauthRedirectPort = 0)
          // Play the browser: parse the real state from the auth URL login
          // built, then GET the loopback callback with code + that state.
          McpOAuth.authUrlHook = Present { authUrl =>
            val params  = McpOAuth.parseQuery(authUrl)
            val state   = params.getOrElse("state", "")
            val redirect = params.getOrElse("redirect_uri", "")
            val cbUrl = s"$redirect?code=$issuedCode&state=$state"
            Fiber.initUnscoped(
              Async.sleep(200.millis).andThen(
                Abort.run[apollo.http.HttpError](
                  apollo.http.Transport.getJson(cbUrl, Nil, 3.seconds)).unit)
            ).unit
          }
          McpOAuth.login(fixedCfg, store, paste, 30.seconds).map {
              case Result.Success(scope) =>
                assertEquals(scope, "mcp:read")
                // DCR happened; PKCE verifier reached the token endpoint.
                assert(registered.get.contains("dcr-client") || registered.get.contains("redirect_uris"), registered.get)
                assert(seenVerifier.get.nonEmpty, "no code_verifier at token endpoint")
                // Cached tokens now let a real connect succeed and list tools.
                McpManager.tuning = McpTuning()
                McpOAuth.tokenSource(fixedCfg, store, 10.seconds).map { ts =>
                  McpHttpClient.connect("oauthsrv", s"$base/mcp", Nil, 10.seconds, "test", Present(ts)).map {
                    case Result.Success(client) =>
                      client.request("tools/list", Absent).map { r =>
                        client.close.andThen {
                          val names = r.getOrElse(Jx.obj()).field("tools").asArr.getOrElse(Chunk.empty)
                            .flatMap(t => (t / "name").asStr.toList)
                          assertEquals(names.toList, List("secret"))
                          // Rotate the server's accepted token: the next call 401s,
                          // the client refreshes and retries transparently.
                          access.set("access-2")
                          McpHttpClient.connect("oauthsrv", s"$base/mcp", Nil, 10.seconds, "test", Present(ts)).map {
                            case Result.Success(c2) =>
                              c2.request("tools/list", Absent).map { r2 =>
                                c2.close.andThen(assert(r2.isSuccess, s"refresh-retry failed: $r2"))
                              }
                            case other => throw new AssertionError(other.toString)
                          }
                        }
                      }
                    case other => throw new AssertionError(other.toString)
                  }
                }
              case kyo.Result.Failure(err) => throw new AssertionError(s"login failed: $err")
              case kyo.Result.Panic(e)     => throw e
            }
      }
    }
  }

  /** A likely-free localhost port (bind :0, read it, release). */
  private def ephemeralPort(): Int =
    val s = new java.net.ServerSocket(0)
    val p = s.getLocalPort
    s.close()
    p
end McpOAuthSuite

package apollo.mcp

import apollo.config.ApolloPaths
import apollo.util.Jx
import apollo.util.Jx.*
import kyo.*
import kyo.Structure.Value

/** Registered OAuth client for one server (from dynamic client registration
  * or static config).
  */
final case class OAuthClient(clientId: String, clientSecret: Maybe[String], tokenAuthMethod: String)

/** Discovered authorization-server endpoints for one MCP server. */
final case class OAuthEndpoints(
    authorizationEndpoint: String,
    tokenEndpoint: String,
    registrationEndpoint: Maybe[String]
)

/** Persisted tokens plus an absolute expiry (epoch millis; 0 = unknown). */
final case class OAuthTokens(
    accessToken: String,
    refreshToken: Maybe[String],
    tokenType: String,
    scope: Maybe[String],
    expiresAtMillis: Long
):
  /** True within `skewMillis` of expiry (refresh-ahead margin). */
  def expiresWithin(skewMillis: Long, nowMillis: Long): Boolean =
    expiresAtMillis != 0L && nowMillis >= expiresAtMillis - skewMillis

/** Per-server OAuth state on disk under `<home>/mcp-tokens/`, mirroring the
  * upstream `HermesTokenStorage` layout: `<name>.json` (tokens, with an
  * absolute `expires_at`), `<name>.client.json` (registration), and
  * `<name>.meta.json` (endpoints). Files are written 0600 (best effort) and
  * keyed by a sanitized server name.
  */
final class McpOAuthStore(paths: ApolloPaths):

  private def dir = paths.mcpTokensDir

  /** Upstream `_safe_filename`. */
  private def safe(name: String): String =
    val s = name.replaceAll("[^\\w\\-]", "_").replaceAll("^_+|_+$", "").take(128)
    if s.isEmpty then "default" else s

  private def tokensPath(name: String) = dir.resolve(s"${safe(name)}.json")
  private def clientPath(name: String) = dir.resolve(s"${safe(name)}.client.json")
  private def metaPath(name: String)   = dir.resolve(s"${safe(name)}.meta.json")

  private def write0600(path: java.nio.file.Path, content: String): Unit =
    import java.nio.file.*
    import java.nio.file.attribute.PosixFilePermission
    Files.createDirectories(path.getParent)
    val tmp = path.resolveSibling(s"${path.getFileName}.tmp.${java.lang.System.nanoTime()}")
    Files.write(tmp, content.getBytes("UTF-8"))
    Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
    try
      val perms = java.util.EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
      Files.setPosixFilePermissions(path, perms)
      Files.setPosixFilePermissions(path.getParent,
        java.util.EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
          PosixFilePermission.OWNER_EXECUTE))
    catch case _: Exception => () // non-POSIX filesystem: best effort

  private def readValue(path: java.nio.file.Path): Maybe[Value] < Sync =
    apollo.config.Fs.readString(path).map {
      case Present(text) => Jx.parse(text) match
          case Result.Success(v) => Present(v)
          case _                 => Absent
      case Absent => Absent
    }

  // --- tokens --------------------------------------------------------------

  def saveTokens(name: String, t: OAuthTokens): Unit < Sync =
    Sync.defer(write0600(tokensPath(name), Jx.render(Jx.objOf(
      "access_token"  -> Present(Jx.str(t.accessToken)),
      "refresh_token" -> t.refreshToken.map(Jx.str),
      "token_type"    -> Present(Jx.str(t.tokenType)),
      "scope"         -> t.scope.map(Jx.str),
      "expires_at"    -> Present(Jx.num(t.expiresAtMillis))
    ))))

  def loadTokens(name: String): Maybe[OAuthTokens] < Sync =
    readValue(tokensPath(name)).map(_.flatMap { v =>
      (v / "access_token").asStr.map { at =>
        OAuthTokens(
          accessToken = at,
          refreshToken = (v / "refresh_token").asStr,
          tokenType = (v / "token_type").asStr.getOrElse("Bearer"),
          scope = (v / "scope").asStr,
          expiresAtMillis = (v / "expires_at").asLong.getOrElse(0L)
        )
      }
    })

  // --- client registration -------------------------------------------------

  def saveClient(name: String, c: OAuthClient): Unit < Sync =
    Sync.defer(write0600(clientPath(name), Jx.render(Jx.objOf(
      "client_id"                  -> Present(Jx.str(c.clientId)),
      "client_secret"              -> c.clientSecret.map(Jx.str),
      "token_endpoint_auth_method" -> Present(Jx.str(c.tokenAuthMethod))
    ))))

  def loadClient(name: String): Maybe[OAuthClient] < Sync =
    readValue(clientPath(name)).map(_.flatMap { v =>
      (v / "client_id").asStr.map { id =>
        val secret = (v / "client_secret").asStr
        OAuthClient(id, secret,
          (v / "token_endpoint_auth_method").asStr
            .getOrElse(if secret.nonEmpty then "client_secret_post" else "none"))
      }
    })

  // --- endpoints -----------------------------------------------------------

  def saveEndpoints(name: String, e: OAuthEndpoints): Unit < Sync =
    Sync.defer(write0600(metaPath(name), Jx.render(Jx.objOf(
      "authorization_endpoint" -> Present(Jx.str(e.authorizationEndpoint)),
      "token_endpoint"         -> Present(Jx.str(e.tokenEndpoint)),
      "registration_endpoint"  -> e.registrationEndpoint.map(Jx.str)
    ))))

  def loadEndpoints(name: String): Maybe[OAuthEndpoints] < Sync =
    readValue(metaPath(name)).map(_.flatMap { v =>
      for
        auth  <- (v / "authorization_endpoint").asStr
        token <- (v / "token_endpoint").asStr
      yield OAuthEndpoints(auth, token, (v / "registration_endpoint").asStr)
    })

  /** `apollo mcp logout`: delete every state file for the server. */
  def remove(name: String): Unit < Sync =
    Sync.defer {
      List(tokensPath(name), clientPath(name), metaPath(name)).foreach { p =>
        try java.nio.file.Files.deleteIfExists(p) catch case _: Exception => ()
      }
    }
end McpOAuthStore

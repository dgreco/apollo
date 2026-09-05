package apollo.mcp

import apollo.config.{ApolloConfig, Fs}
import apollo.config.Yaml.*
import apollo.tools.Detection
import kyo.*
import org.virtuslab.yaml.Node

/** One entry of the top-level `mcp_servers:` map, parsed with the upstream
  * harness's key set and coercion rules (`mcp_tool_config.py` /
  * `mcp_tool_common.py`): bool-ish strings, `${VAR}` interpolation with
  * unresolved refs kept verbatim, `tools.include` presence switching to
  * whitelist mode, and per-server timeouts falling back to
  * `timeouts.mcp.tool_call` then 300s.
  */
final case class McpServerConfig(
    name: String,
    enabled: Boolean,
    command: Maybe[String],
    args: List[String],
    env: Map[String, String],
    cwd: Maybe[String],
    url: Maybe[String],
    headers: List[(String, String)],
    transport: Maybe[String],
    auth: Maybe[String],
    connectTimeoutSeconds: Double,
    toolTimeoutSeconds: Double,
    keepaliveIntervalSeconds: Double,
    include: Maybe[List[String]],
    exclude: List[String],
    resourceTools: Boolean,
    promptTools: Boolean,
    untrusted: Boolean,
    /** `oauth:` sub-block: a pre-registered client (skips dynamic client
      * registration) and requested scopes.
      */
    oauthClientId: Maybe[String],
    oauthClientSecret: Maybe[String],
    oauthScopes: List[String],
    /** Pin the loopback callback port (`oauth.redirect_port`); 0 = ephemeral. */
    oauthRedirectPort: Int
):
  /** stdio unless a `url` is present (upstream transport discriminator). */
  def isStdio: Boolean = url.isEmpty

  /** True when this server should authenticate via OAuth 2.1 (PKCE). */
  def usesOAuth: Boolean = auth.contains("oauth")

  /** Include (whitelist, even when empty) wins over exclude; both accept
    * exact names and fnmatch globs against the RAW tool name.
    */
  def toolAllowed(rawName: String): Boolean =
    def hit(patterns: List[String]): Boolean =
      patterns.exists(p => p == rawName || Detection.fnmatch(p).matches(rawName))
    include match
      case Present(patterns) => hit(patterns)
      case Absent            => !hit(exclude)
end McpServerConfig

object McpConfig:

  /** Upstream `_DEFAULT_CONNECT_TIMEOUT` / `_DEFAULT_TOOL_TIMEOUT`. */
  val defaultConnectTimeout = 60.0
  val defaultToolTimeout    = 300.0

  /** Fractional-second config values as kyo durations. */
  def duration(seconds: Double): Duration = (seconds * 1000).toLong.millis

  /** Parses `mcp_servers:` with interpolation and the security filter
    * applied; flagged or disabled entries are dropped (disabled silently,
    * suspicious with the returned warning list).
    */
  def load(config: ApolloConfig, workspace: java.nio.file.Path): (List[McpServerConfig], List[String]) =
    if safeMode(config) then (Nil, Nil)
    else
      val globalToolTimeout =
        config.root.flatMap(_.path("timeouts", "mcp", "tool_call")).flatMap(_.double)
          .getOrElse(defaultToolTimeout)
      val parsed = config.root.flatMap(_.path("mcp_servers")).flatMap(_.entries).getOrElse(Nil)
        .map((name, node) => parseServer(name, node, config, workspace, globalToolTimeout))
      val (suspicious, ok) = parsed.partitionMap { s =>
        McpSecurity.validate(s) match
          case Present(reason) => Left(reason)
          case Absent          => Right(s)
      }
      (ok.filter(_.enabled), suspicious)

  /** `APOLLO_SAFE_MODE` (legacy `HERMES_SAFE_MODE`) disables MCP entirely. */
  private def safeMode(config: ApolloConfig): Boolean =
    List("APOLLO_SAFE_MODE", "HERMES_SAFE_MODE")
      .exists(k => config.env.get(k).exists(v => truthy(v.trim.toLowerCase)))

  private val trueWords  = Set("true", "1", "yes", "on")
  private val falseWords = Set("false", "0", "no", "off")
  private def truthy(s: String): Boolean = trueWords.contains(s)

  /** Upstream `_parse_boolish`: real bools or the true/false word sets,
    * case-insensitive; anything else keeps the default.
    */
  def boolish(node: Maybe[Node], default: Boolean): Boolean =
    node.flatMap(_.str).map(_.trim.toLowerCase) match
      case Present(w) if trueWords.contains(w)  => true
      case Present(w) if falseWords.contains(w) => false
      case _                                    => default

  /** Discovery wait bound: `mcp_discovery_timeout` for interactive sessions,
    * `mcp_single_query_discovery_timeout` for one-shots (upstream defaults
    * 1.5s / 15s; this build waits the one-shot bound everywhere because tool
    * lists are fixed at session assembly — servers finishing later would
    * register tools the model never sees).
    */
  def discoveryTimeoutSeconds(config: ApolloConfig): Double =
    config.root.flatMap(_.path("mcp_single_query_discovery_timeout")).flatMap(_.double)
      .orElse(config.root.flatMap(_.path("mcp_discovery_timeout")).flatMap(_.double))
      .getOrElse(15.0)

  private def parseServer(
      name: String,
      node: Node,
      config: ApolloConfig,
      workspace: java.nio.file.Path,
      globalToolTimeout: Double
  ): McpServerConfig =
    def interp(s: String): String = Interpolate(s, config.env.get, workspace)
    val tools = node.field("tools")
    // Presence of the include key — in any scalar/list form — switches to
    // whitelist mode; `include: []` therefore registers nothing.
    val include = tools.flatMap(_.field("include")).map(n => n.strings.getOrElse(Nil).map(interp))
    val trust   = node.field("trust").flatMap(_.str).map(_.trim.toLowerCase)
    McpServerConfig(
      name = name,
      enabled = boolish(node.field("enabled"), default = true),
      command = node.field("command").flatMap(_.str).map(c => Fs.expand(interp(c).trim, config.env.get)),
      args = node.field("args").flatMap(_.strings).getOrElse(Nil).map(interp),
      env = node.field("env").flatMap(_.entries).getOrElse(Nil)
        .flatMap((k, v) => v.str.map(s => k -> interp(s)).toList).toMap,
      cwd = node.field("cwd").flatMap(_.str).map(interp),
      url = node.field("url").flatMap(_.str).map(interp),
      headers = node.field("headers").flatMap(_.entries).getOrElse(Nil)
        .flatMap((k, v) => v.str.map(s => k -> interp(s)).toList),
      transport = node.field("transport").flatMap(_.str).map(_.trim.toLowerCase),
      auth = node.field("auth").flatMap(_.str).map(_.trim.toLowerCase),
      connectTimeoutSeconds = node.field("connect_timeout").flatMap(_.double)
        .getOrElse(defaultConnectTimeout).max(1.0),
      toolTimeoutSeconds = node.field("timeout").flatMap(_.double).getOrElse(globalToolTimeout),
      // Upstream `_DEFAULT_KEEPALIVE_INTERVAL, _MIN_KEEPALIVE_INTERVAL = 180, 5`.
      keepaliveIntervalSeconds = node.field("keepalive_interval").flatMap(_.double)
        .getOrElse(180.0).max(5.0),
      include = include,
      exclude = tools.flatMap(_.field("exclude")).flatMap(_.strings).getOrElse(Nil).map(interp),
      resourceTools = boolish(tools.flatMap(_.field("resources")), default = true),
      promptTools = boolish(tools.flatMap(_.field("prompts")), default = true),
      // Missing trust = full; unrecognized values fail closed to untrusted.
      untrusted = trust.exists(_ != "full"),
      oauthClientId = node.field("oauth").flatMap(_.field("client_id")).flatMap(_.str).map(interp),
      oauthClientSecret = node.field("oauth").flatMap(_.field("client_secret")).flatMap(_.str).map(interp),
      oauthScopes = node.field("oauth").flatMap(_.field("scopes")).flatMap(_.strings)
        .orElse(node.field("oauth").flatMap(_.field("scope")).flatMap(_.strings))
        .getOrElse(Nil).flatMap(_.split("\\s+")).map(_.trim).filter(_.nonEmpty),
      oauthRedirectPort = node.field("oauth").flatMap(_.field("redirect_port")).flatMap(_.int).getOrElse(0)
    )
  end parseServer

  // --- subprocess environment --------------------------------------------

  /** Upstream `_SAFE_ENV_KEYS`: the child does NOT inherit the full parent
    * environment — only these keys (plus `XDG_*`), with the server config's
    * `env` overlaid on top.
    */
  private val safeEnvKeys = Set(
    "PATH", "HOME", "USER", "LANG", "LC_ALL", "TERM", "SHELL", "TMPDIR",
    // Windows set, matched case-insensitively upstream; harmless elsewhere.
    "ALLUSERSPROFILE", "APPDATA", "COMSPEC", "SYSTEMROOT", "TEMP", "TMP", "PATHEXT"
  )

  def safeEnv(parentEnv: Map[String, String], serverEnv: Map[String, String]): Map[String, String] =
    parentEnv.filter { (k, _) =>
      safeEnvKeys.contains(k) || safeEnvKeys.contains(k.toUpperCase) || k.startsWith("XDG_")
    } ++ serverEnv
end McpConfig

/** Upstream `_interpolate_env_vars`: `${VAR}` (any non-`}` body, so dots and
  * dashes work) resolved from the env chain, Cursor-style `${env:VAR}` and
  * Cursor context variables accepted, unresolved refs kept verbatim.
  */
object Interpolate:

  private val pattern = """\$\{([^}]+)\}""".r

  def apply(value: String, env: String => Maybe[String], workspace: java.nio.file.Path): String =
    pattern.replaceAllIn(value, m =>
      val body = m.group(1).trim
      val resolved: Maybe[String] = body match
        case "userHome"                => Present(Fs.userHome)
        case "workspaceFolder"         => Present(workspace.toString)
        case "workspaceFolderBasename" => Present(workspace.getFileName.toString)
        case "pathSeparator" | "/"     => Present(java.io.File.separator)
        case other =>
          val name = if other.startsWith("env:") then other.drop(4) else other
          env(name)
      java.util.regex.Matcher.quoteReplacement(resolved.getOrElse(m.matched))
    )
end Interpolate

/** Load-time refusal of server entries whose spawn shape matches the
  * upstream `validate_mcp_server_entry` heuristics: a shell interpreter
  * driving network egress or writing OS persistence surfaces. Not a
  * whitelist — a flagged entry is dropped with a warning, everything else
  * runs (subject to approval gating for `trust: untrusted` servers).
  */
object McpSecurity:

  private val shellInterpreters = Set(
    "bash", "sh", "zsh", "dash", "fish", "cmd", "cmd.exe",
    "powershell", "powershell.exe", "pwsh", "pwsh.exe"
  )

  private val egressPatterns = List(
    """\b(curl|wget|nc|ncat|socat)\b""".r,
    """/dev/tcp/""".r,
    """Invoke-WebRequest""".r,
    """Invoke-RestMethod""".r,
    """System\.Net\.WebClient""".r
  )

  private val persistencePatterns = List(
    """authorized_keys""".r,
    """\.ssh/""".r,
    """/etc/ssh""".r,
    """/etc/pam""".r, """pam\.d""".r,
    """sudoers""".r,
    """\bcrontab\b""".r, """/etc/cron""".r,
    """systemd""".r, """rc\.local""".r,
    """\.(bashrc|zshrc|profile|bash_profile)\b""".r
  )

  /** Returns the refusal reason, or Absent when the entry is acceptable. */
  def validate(server: McpServerConfig): Maybe[String] =
    server.command.map(c => java.nio.file.Paths.get(c).getFileName.toString.toLowerCase) match
      case Present(base) if shellInterpreters.contains(base) =>
        val flat = server.args.mkString(" ")
        if egressPatterns.exists(_.findFirstIn(flat).isDefined) then
          Present(s"mcp server '${server.name}' dropped: shell interpreter with network egress in args")
        else if persistencePatterns.exists(_.findFirstIn(flat).isDefined) then
          Present(s"mcp server '${server.name}' dropped: shell interpreter touching persistence surfaces")
        else Absent
      case _ => Absent
end McpSecurity

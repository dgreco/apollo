package apollo.config

import kyo.*
import org.virtuslab.yaml.Node
import Yaml.*

/** Typed, lenient view over `<home>/config.yaml` + the env chain.
  *
  * the upstream harness's config is huge and ever-growing; we keep the raw AST and expose
  * typed accessors for the keys this implementation acts on. Unknown keys are
  * preserved-and-ignored, so a real upstream `config.yaml` loads unchanged.
  * `${VAR}` references (and Cursor-style `${env:VAR}`) in string values are
  * expanded from the env chain; unresolvable ones are kept verbatim, matching
  * the upstream harness.
  */
final case class ApolloConfig(root: Maybe[Node], env: EnvChain, paths: ApolloPaths):

  private def at(keys: String*): Maybe[Node] = root.flatMap(_.path(keys*))

  /** String value with `${VAR}` / `${env:VAR}` expansion. */
  private def strAt(keys: String*): Maybe[String] =
    at(keys*).flatMap(_.str).map(expandVars)

  def expandVars(value: String): String =
    val pattern = """\$\{(?:env:)?([A-Za-z_][A-Za-z0-9_]*)\}""".r
    pattern.replaceAllIn(value, m => java.util.regex.Matcher.quoteReplacement(env.get(m.group(1)).getOrElse(m.matched)))

  // --- model section ------------------------------------------------------
  // Root-level `provider`/`base_url`/`api_base`/`context_length` are legacy
  // layouts hoisted under `model` when the model.* key is empty, `api_base`
  // aliases `base_url`, a scalar `model:` is the model id, and a dict-valued
  // id is flattened — upstream `_normalize_root_model_keys` semantics,
  // implemented at the accessors so both user and managed nodes benefit.

  /** `model.default` > `model.model` > `model.name` (dict values flattened
    * via their `model`/`default` entry) > scalar `model:` at the root.
    */
  def modelDefault: Maybe[String] =
    def idAt(key: String): Maybe[String] =
      at("model", key).flatMap { node =>
        node.str.orElse(node.field("model").flatMap(_.str)).orElse(node.field("default").flatMap(_.str))
      }.map(expandVars).filter(_.nonEmpty)
    idAt("default")
      .orElse(idAt("model"))
      .orElse(idAt("name"))
      .orElse(at("model").flatMap(_.str).map(expandVars).filter(_.nonEmpty))

  def modelProvider: Maybe[String] =
    strAt("model", "provider").filter(_.nonEmpty)
      .orElse(strAt("provider").filter(_.nonEmpty)) // root hoist, never overriding

  def modelBaseUrl: Maybe[String] =
    strAt("model", "base_url")
      .orElse(strAt("model", "api_base")) // issue #8919 alias
      .orElse(strAt("base_url"))
      .orElse(strAt("api_base"))

  def modelApiKey: Maybe[String]    = strAt("model", "api_key")
  /** `model.key_cmd`: a command that prints a (usually short-lived) token —
    * mints credentials for OAuth-token CLIs (`gcloud auth print-access-token`
    * for Vertex, `az account get-access-token` for Azure Entra, etc.). Applies
    * to any profile; beaten only by an explicit `--api-key`. */
  def modelKeyCmd: Maybe[String]    = strAt("model", "key_cmd").filter(_.nonEmpty)
  def modelApiMode: Maybe[String]   = strAt("model", "api_mode").filter(_.nonEmpty)
  def modelStreaming: Boolean       = at("model", "streaming").flatMap(_.bool).getOrElse(true)
  def modelMaxTokens: Maybe[Int]    = at("model", "max_tokens").flatMap(_.int)
  def contextLength: Maybe[Int] =
    at("model", "context_length").flatMap(_.int).orElse(at("context_length").flatMap(_.int))

  def modelDefaultHeaders: Map[String, String] =
    val defaults = at("model", "default_headers").flatMap(_.entries).getOrElse(Nil)
    val extras   = at("model", "extra_headers").flatMap(_.entries).getOrElse(Nil)
    (defaults ++ extras).flatMap((k, v) => v.str.map(s => k -> expandVars(s)).toList).toMap

  /** Named custom providers (`providers:` map). */
  def namedProviders: List[CustomProvider] =
    at("providers").flatMap(_.entries).getOrElse(Nil).map { (key, node) =>
      CustomProvider(
        key = key,
        baseUrl = node.field("base_url").orElse(node.field("baseUrl")).orElse(node.field("url"))
          .orElse(node.field("api")).flatMap(_.str).map(expandVars),
        apiKey = node.field("api_key").orElse(node.field("apiKey")).flatMap(_.str).map(expandVars),
        keyEnv = node.field("key_env").orElse(node.field("keyEnv")).orElse(node.field("api_key_env"))
          .orElse(node.field("apiKeyEnv")).flatMap(_.str),
        keyCmd = node.field("key_cmd").flatMap(_.str),
        apiMode = node.field("api_mode").orElse(node.field("apiMode")).orElse(node.field("transport"))
          .flatMap(_.str),
        model = node.field("model").orElse(node.field("default_model")).flatMap(_.str),
        extraHeaders = node.field("extra_headers").flatMap(_.entries).getOrElse(Nil)
          .flatMap((k, v) => v.str.map(s => k -> expandVars(s)).toList).toMap,
        requestTimeoutSeconds = node.field("request_timeout_seconds").flatMap(_.int)
      )
    }

  /** `model_aliases:` — short names resolved before catalogs. */
  def modelAliases: Map[String, ModelAlias] =
    at("model_aliases").flatMap(_.entries).getOrElse(Nil).map { (name, node) =>
      name -> ModelAlias(
        model = node.field("model").flatMap(_.str).getOrElse(name),
        provider = node.field("provider").flatMap(_.str),
        baseUrl = node.field("base_url").flatMap(_.str).map(expandVars),
        apiKey = node.field("api_key").flatMap(_.str).map(expandVars),
        keyEnv = node.field("key_env").flatMap(_.str)
      )
    }.toMap

  def fallbackProviders: List[String] =
    at("fallback_providers").flatMap(_.strings).getOrElse(Nil)

  // --- agent section ------------------------------------------------------

  /** `agent.max_turns`: null/absent = unlimited (upstream default). */
  def maxTurns: Maybe[Int]        = at("agent", "max_turns").flatMap(_.int)
  def apiMaxRetries: Int          = at("agent", "api_max_retries").flatMap(_.int).getOrElse(3).max(1)
  def verbose: Boolean            = at("agent", "verbose").flatMap(_.bool).getOrElse(false)
  /** `agent.repetition_limit`: break the turn when the model emits the same
    * tool-call (or final-text) signature this many times in a row (0 = off). */
  def repetitionLimit: Int        = at("agent", "repetition_limit").flatMap(_.int).getOrElse(4).max(0)
  /** `agent.empty_response_retries`: how many times to re-prompt the model in
    * one turn when it returns neither text nor tool calls (0 = never retry). */
  def emptyResponseRetries: Int   = at("agent", "empty_response_retries").flatMap(_.int).getOrElse(1).max(0)
  /** `prompt_cache.enabled` (fallback `agent.prompt_cache`): attach provider
    * prompt-cache breakpoints to the stable prefix (Anthropic today). */
  def promptCacheEnabled: Boolean =
    at("prompt_cache", "enabled").flatMap(_.bool)
      .orElse(at("agent", "prompt_cache").flatMap(_.bool)).getOrElse(true)
  /** `agent.auto_review`: after every `auto_review_interval` REPL turns, fork a
    * background reviewer that actually saves durable memories/skills (vs the
    * nudges, which only remind). Opt-in — it spends an extra model call. */
  def autoReviewEnabled: Boolean  = at("agent", "auto_review").flatMap(_.bool).getOrElse(false)
  def autoReviewInterval: Int     = at("agent", "auto_review_interval").flatMap(_.int).getOrElse(5).max(1)
  /** `browser.enabled`: allow the `browser` tool to launch a headless Chrome
    * (an already-running CDP endpoint via CHROME_CDP_URL works regardless). */
  def browserEnabled: Boolean     = at("browser", "enabled").flatMap(_.bool).getOrElse(false)
  def reasoningEffort: String     = strAt("agent", "reasoning_effort").getOrElse("medium")
  def reasoningOverrides: List[(String, String)] =
    at("agent", "reasoning_overrides").flatMap(_.entries).getOrElse(Nil)
      .flatMap((k, v) => v.str.map(k -> _).toList)
  def codingInstructions: List[String] =
    at("agent", "coding_instructions").flatMap(_.strings).getOrElse(Nil)
  /** Wall-clock cap for one gateway agent turn (`agent.gateway_timeout`,
    * seconds; 0 = unlimited). A stuck turn otherwise holds its session's
    * mutex forever.
    */
  def gatewayTimeoutSeconds: Int =
    at("agent", "gateway_timeout").flatMap(_.int).getOrElse(1800)

  /** Keep a live "typing…" indicator visible on chat platforms for the whole
    * turn (`display.long_running_notifications`, default true).
    */
  def longRunningNotifications: Boolean =
    at("display", "long_running_notifications").flatMap(_.bool).getOrElse(true)

  /** Send a one-line acknowledgment when a new message arrives while a turn
    * for that same chat is still running (`display.busy_ack`, default true).
    */
  def busyAck: Boolean =
    at("display", "busy_ack").flatMap(_.bool).getOrElse(true)

  def disabledToolsets: List[String] =
    at("agent", "disabled_toolsets").flatMap(_.strings).getOrElse(Nil)

  // --- approvals ----------------------------------------------------------

  def approvalMode: String        = strAt("approvals", "mode").getOrElse("manual")
  def approvalTimeoutSeconds: Int = at("approvals", "timeout").flatMap(_.int).getOrElse(300)
  def approvalDenyGlobs: List[String] =
    at("approvals", "deny").flatMap(_.strings).getOrElse(Nil)
  def unattendedApprovalMode: String = strAt("approvals", "unattended_mode").getOrElse("deny")
  def cronApprovalMode: String       = strAt("approvals", "cron_mode").getOrElse("deny")
  def singleQueryApprovalMode: String = strAt("approvals", "single_query_mode").getOrElse("deny")

  // --- terminal -----------------------------------------------------------

  def terminalBackend: String     = strAt("terminal", "backend").getOrElse("local")
  def terminalCwd: String         = strAt("terminal", "cwd").getOrElse(".")
  def terminalTimeoutSeconds: Int = at("terminal", "timeout").flatMap(_.int).getOrElse(180)

  /** Docker backend (`terminal.backend: docker`): exec commands inside an
    * already-running container. `container` is required; `workdir` and `env`
    * are optional. Image-based container lifecycle (starting/tearing down
    * containers) is out of scope — point at a running container.
    */
  def terminalDockerContainer: Maybe[String] =
    strAt("terminal", "docker", "container").filter(_.nonEmpty)
      .orElse(env.get("TERMINAL_DOCKER_CONTAINER").filter(_.nonEmpty))
  def terminalDockerWorkdir: Maybe[String] =
    strAt("terminal", "docker", "workdir").filter(_.nonEmpty)
  def terminalDockerEnv: Map[String, String] =
    at("terminal", "docker", "env").flatMap(_.entries).getOrElse(Nil)
      .flatMap((k, v) => v.str.map(s => k -> expandVars(s)).toList).toMap
  def terminalDockerExtraArgs: List[String] =
    at("terminal", "docker", "extra_args").flatMap(_.strings).getOrElse(Nil)

  /** SSH backend (`terminal.backend: ssh`): run commands on a remote host via
    * the local `ssh` client. `host` is required; `user`/`port`/`key_path`/
    * `workdir` are optional. Connection reuse and remote container lifecycle
    * are out of scope — a plain `ssh … sh -c` per command.
    */
  def terminalSshHost: Maybe[String] =
    strAt("terminal", "ssh", "host").filter(_.nonEmpty)
      .orElse(env.get("TERMINAL_SSH_HOST").filter(_.nonEmpty))
  def terminalSshUser: Maybe[String]    = strAt("terminal", "ssh", "user").filter(_.nonEmpty)
  def terminalSshPort: Maybe[Int]       = at("terminal", "ssh", "port").flatMap(_.int)
  def terminalSshKeyPath: Maybe[String] =
    strAt("terminal", "ssh", "key_path").map(p => Fs.expand(p, env.get))
  def terminalSshWorkdir: Maybe[String] = strAt("terminal", "ssh", "workdir").filter(_.nonEmpty)
  def terminalSshExtraArgs: List[String] =
    at("terminal", "ssh", "extra_args").flatMap(_.strings).getOrElse(Nil)

  /** Singularity/Apptainer backend (`terminal.backend: singularity`): exec
    * inside a container image via the `singularity`/`apptainer` CLI (HPC
    * container runtime). `image` (a SIF path or URI) is required. */
  def terminalSingularityImage: Maybe[String] =
    strAt("terminal", "singularity", "image").filter(_.nonEmpty)
      .orElse(env.get("TERMINAL_SINGULARITY_IMAGE").filter(_.nonEmpty))
  def terminalSingularityBinary: String =
    strAt("terminal", "singularity", "binary").filter(_.nonEmpty).getOrElse("singularity")
  def terminalSingularityExtraArgs: List[String] =
    at("terminal", "singularity", "extra_args").flatMap(_.strings).getOrElse(Nil)

  /** Generic exec backend (`terminal.backend: exec`): prefix every command
    * with a user-configured argv, so any sandbox CLI works without a bespoke
    * backend — podman, kubectl, nsjail/firejail/bwrap, or a cloud sandbox
    * (modal/daytona/vercel) via its own CLI. apollo appends `sh -c '<cmd>'`
    * unless `raw` is set, when it appends the command as a single verbatim arg.
    */
  def terminalExecArgv: List[String] =
    at("terminal", "exec", "argv").flatMap(_.strings).getOrElse(Nil)
  def terminalExecRaw: Boolean =
    at("terminal", "exec", "raw").flatMap(_.bool).getOrElse(false)

  // --- compression --------------------------------------------------------

  def compressionEnabled: Boolean   = at("compression", "enabled").flatMap(_.bool).getOrElse(true)
  def compressionThreshold: Double  = at("compression", "threshold").flatMap(_.double).getOrElse(0.5)
  def protectFirstN: Int            = at("compression", "protect_first_n").flatMap(_.int).getOrElse(3)
  def protectLastN: Int             = at("compression", "protect_last_n").flatMap(_.int).getOrElse(20)
  def minTailUserMessages: Int      = at("compression", "min_tail_user_messages").flatMap(_.int).getOrElse(1)
  def compressionMaxAttempts: Int   = at("compression", "max_attempts").flatMap(_.int).getOrElse(3).max(1).min(10)

  // --- memory -------------------------------------------------------------

  def memoryEnabled: Boolean      = at("memory", "memory_enabled").flatMap(_.bool).getOrElse(true)
  def userProfileEnabled: Boolean = at("memory", "user_profile_enabled").flatMap(_.bool).getOrElse(true)
  def memoryCharLimit: Int        = at("memory", "memory_char_limit").flatMap(_.int).getOrElse(2200)
  def userCharLimit: Int          = at("memory", "user_char_limit").flatMap(_.int).getOrElse(1375)
  def memoryNudgeInterval: Int    = at("memory", "nudge_interval").flatMap(_.int).getOrElse(10)

  // --- skills -------------------------------------------------------------

  def skillsExternalDirs: List[String] =
    at("skills", "external_dirs").flatMap(_.strings).getOrElse(Nil).map(p => Fs.expand(expandVars(p), env.get))
  def skillsDisabled: List[String] =
    at("skills", "disabled").flatMap(_.strings).getOrElse(Nil)
  def skillCreationNudgeInterval: Int =
    at("skills", "creation_nudge_interval").flatMap(_.int).getOrElse(15)
  /** Skills Hub catalog (a JSON index of installable skills) for `apollo skills search`. */
  def skillsHubCatalogUrl: Maybe[String] =
    strAt("skills", "hub_catalog_url").orElse(env.get("SKILLS_HUB_CATALOG_URL"))

  // --- display ------------------------------------------------------------

  def displayInterface: String  = strAt("display", "interface").getOrElse("cli")
  def displayCompact: Boolean   = at("display", "compact").flatMap(_.bool).getOrElse(false)
  def displayStreaming: Boolean = at("display", "streaming").flatMap(_.bool).getOrElse(true)
  def showReasoning: Boolean    = at("display", "show_reasoning").flatMap(_.bool).getOrElse(false)
  def toolProgress: String      = strAt("display", "tool_progress").getOrElse("all")
  /** Opt-in concurrent-input REPL (`display.async_input`): turns run on a fiber
    * while the prompt stays live for /steer, /stop, /queue. JVM/JLine only. */
  def asyncInput: Boolean       = at("display", "async_input").flatMap(_.bool).getOrElse(false)

  // Image generation (`image.*`), used by the image_generate tool. Key falls
  // back to OPENAI_API_KEY; base/model default to OpenAI's images API.
  def imageApiKey: Maybe[String] =
    strAt("image", "api_key").orElse(env.get("IMAGE_API_KEY")).orElse(env.get("OPENAI_API_KEY"))
  def imageApiBase: String =
    strAt("image", "api_base").orElse(env.get("IMAGE_API_BASE")).getOrElse("https://api.openai.com/v1")
  def imageModel: String =
    strAt("image", "model").orElse(env.get("IMAGE_MODEL")).getOrElse("dall-e-3")

  // Video generation (`video.*`), used by the video_generate tool. Async
  // submit → poll → download, OpenAI Sora-shaped. Key falls back to
  // IMAGE_API_KEY / OPENAI_API_KEY.
  def videoApiKey: Maybe[String] =
    strAt("video", "api_key").orElse(env.get("VIDEO_API_KEY"))
      .orElse(env.get("IMAGE_API_KEY")).orElse(env.get("OPENAI_API_KEY"))
  def videoApiBase: String =
    strAt("video", "api_base").orElse(env.get("VIDEO_API_BASE")).getOrElse("https://api.openai.com/v1")
  def videoModel: String =
    strAt("video", "model").orElse(env.get("VIDEO_MODEL")).getOrElse("sora-2")
  def videoPollSeconds: Int =
    at("video", "poll_seconds").flatMap(_.int).getOrElse(5).max(1)
  def videoMaxPolls: Int =
    at("video", "max_polls").flatMap(_.int).getOrElse(120).max(1)

  // Text-to-speech (`tts.*`), used by the text_to_speech tool. provider =
  // openai (HTTP /audio/speech), say (macOS `say`), or command.
  def ttsProvider: String =
    strAt("tts", "provider").orElse(env.get("TTS_PROVIDER")).getOrElse(if ttsApiKey.nonEmpty then "openai" else "say")
  def ttsApiKey: Maybe[String] =
    strAt("tts", "api_key").orElse(env.get("TTS_API_KEY")).orElse(env.get("OPENAI_API_KEY"))
  def ttsApiBase: String =
    strAt("tts", "api_base").orElse(env.get("TTS_API_BASE")).getOrElse("https://api.openai.com/v1")
  def ttsModel: String  = strAt("tts", "model").orElse(env.get("TTS_MODEL")).getOrElse("tts-1")
  def ttsVoice: String  = strAt("tts", "voice").orElse(env.get("TTS_VOICE")).getOrElse("alloy")
  def ttsFormat: String = strAt("tts", "format").getOrElse("mp3")
  def ttsCommand: Maybe[String] = strAt("tts", "command")

  // Speech-to-text (`stt.*`), used by the transcribe tool (OpenAI-compatible
  // /audio/transcriptions multipart upload).
  def sttApiKey: Maybe[String] =
    strAt("stt", "api_key").orElse(env.get("STT_API_KEY")).orElse(env.get("OPENAI_API_KEY"))
  def sttApiBase: String =
    strAt("stt", "api_base").orElse(env.get("STT_API_BASE")).getOrElse("https://api.openai.com/v1")
  def sttModel: String = strAt("stt", "model").orElse(env.get("STT_MODEL")).getOrElse("whisper-1")

  // Secrets managers (`secrets.*`). Ordered sources resolved at startup and
  // injected into the env (gap-fill by default; fail-open). Mirrors Hermes.
  def secretsSources: List[String] =
    at("secrets", "sources").flatMap(_.strings).getOrElse(Nil).map(_.toLowerCase)
  /** `secrets.onepassword.env`: ENV_VAR -> `op://vault/item/field` reference. */
  def secretsOnePasswordEnv: List[(String, String)] =
    at("secrets", "onepassword", "env").flatMap(_.entries).getOrElse(Nil)
      .flatMap((k, v) => v.str.map(k -> _).toList)
  /** `secrets.command.env`: ENV_VAR -> a command whose stdout is the value. */
  def secretsCommandEnv: List[(String, String)] =
    at("secrets", "command", "env").flatMap(_.entries).getOrElse(Nil)
      .flatMap((k, v) => v.str.map(k -> _).toList)
  /** `secrets.bitwarden.project`: a Bitwarden Secrets Manager project id. */
  def secretsBitwardenProject: Maybe[String] = strAt("secrets", "bitwarden", "project").filter(_.nonEmpty)
  def secretsOverrideExisting: Boolean = at("secrets", "override_existing").flatMap(_.bool).getOrElse(false)
  def opBinary: String  = strAt("secrets", "onepassword", "binary").getOrElse("op")
  def bwsBinary: String = strAt("secrets", "bitwarden", "binary").getOrElse("bws")

  // --- toolsets -----------------------------------------------------------

  def platformToolsets(platform: String): Maybe[List[String]] =
    at("platform_toolsets", platform).flatMap(_.strings)

  // --- delegation ---------------------------------------------------------

  def delegationMaxIterations: Int = at("delegation", "max_iterations").flatMap(_.int).getOrElse(250)
  /** `delegation.inherit_mcp_toolsets`: children keep the parent's MCP tools
    * even under narrowed toolsets (upstream default true).
    */
  def delegationInheritMcpToolsets: Boolean =
    at("delegation", "inherit_mcp_toolsets").flatMap(_.bool).getOrElse(true)
  def delegationMaxConcurrent: Int =
    at("delegation", "max_concurrent_children").flatMap(_.int).getOrElse(10).max(1)

  // --- gateway / platforms ------------------------------------------------

  def platformConfig(platform: String): Maybe[Node] = at("platforms", platform)
  def platformEnabled(platform: String): Maybe[Boolean] =
    platformConfig(platform).flatMap(_.field("enabled")).flatMap(_.bool)
  def groupSessionsPerUser: Boolean =
    at("group_sessions_per_user").flatMap(_.bool).getOrElse(true)
  def maxConcurrentSessions: Maybe[Int] = at("max_concurrent_sessions").flatMap(_.int)
  def gatewayStreamingEnabled: Boolean  = at("streaming", "enabled").flatMap(_.bool).getOrElse(false)
  def gatewayStreamingEditInterval: Double =
    at("streaming", "edit_interval").flatMap(_.double).getOrElse(0.3)
  def gatewayStreamingBufferThreshold: Int =
    at("streaming", "buffer_threshold").flatMap(_.int).getOrElse(40)

  // --- auxiliary ----------------------------------------------------------

  def auxiliary(task: String): (Maybe[String], Maybe[String]) =
    (strAt("auxiliary", task, "provider").filter(v => v.nonEmpty && v != "auto"),
     strAt("auxiliary", task, "model").filter(_.nonEmpty))

end ApolloConfig

/** One entry of the `providers:` map (a named custom provider). */
final case class CustomProvider(
    key: String,
    baseUrl: Maybe[String],
    apiKey: Maybe[String],
    keyEnv: Maybe[String],
    keyCmd: Maybe[String],
    apiMode: Maybe[String],
    model: Maybe[String],
    extraHeaders: Map[String, String],
    requestTimeoutSeconds: Maybe[Int]
):
  def slug: String = s"custom:$key"

/** One entry of `model_aliases:`. */
final case class ModelAlias(
    model: String,
    provider: Maybe[String],
    baseUrl: Maybe[String],
    apiKey: Maybe[String],
    keyEnv: Maybe[String]
)

object ApolloConfig:

  /** Loads config + env from the upstream home with the full the upstream harness layering:
    * env chain (shell < .env < .op.env gap-fill < managed .env), then
    * `config.yaml` (skipped under `APOLLO_IGNORE_USER_CONFIG=1`), with the
    * managed-scope `config.yaml` deep-merged ON TOP (managed wins at the
    * leaf). A missing config yields all defaults; an unparseable one yields
    * defaults plus the parse error for the caller to surface (interactive
    * surfaces warn; one-shot runs refuse startup, like
    * `require_parseable_user_config`).
    */
  def load(paths: ApolloPaths, ignoreUserConfig: Boolean = false): (ApolloConfig, Maybe[String]) < Sync =
    for
      env         <- EnvChain.load(paths.home)
      ignoreUser   = ignoreUserConfig || env.get("APOLLO_IGNORE_USER_CONFIG").contains("1")
      contentV     = (if ignoreUser then Maybe.empty[String]
                      else Fs.readString(paths.configYaml)): Maybe[String] < Sync
      content     <- contentV
      managedNode <- ManagedScope.configNode
    yield
      val (userNode, parseError) = content match
        case Absent => (Absent, Absent)
        case Present(text) =>
          Yaml.parse(text) match
            case Result.Success(node) => (Present(node), Absent)
            case Result.Failure(err)  => (Absent, Present(err))
            case _                    => (Absent, Present("unknown YAML error"))
      val merged = (userNode, managedNode) match
        case (Present(u), Present(m)) => Present(Yaml.deepMerge(u, m))
        case (Present(u), Absent)     => Present(u)
        case (Absent, Present(m))     => Present(m)
        case _                        => Absent
      (ApolloConfig(merged, env, paths), parseError)
end ApolloConfig

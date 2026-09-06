package apollo.provider

import apollo.config.{CustomProvider, ApolloConfig}
import apollo.util.Jx
import apollo.util.Jx.*
import kyo.*

/** Fully-resolved provider runtime for one session: everything a wire
  * transport needs to talk to the endpoint.
  */
final case class ResolvedRuntime(
    providerSlug: String,
    displayName: String,
    model: String,
    baseUrl: String,
    apiKey: Maybe[String],
    apiMode: ApiMode,
    headers: Map[String, String],
    profile: Maybe[Profile],
    reasoning: Maybe[Reasoning.Config],
    maxTokens: Maybe[Int],
    contextLength: Maybe[Int],
    streaming: Boolean
):
  /** The wire model id: a matching provider prefix is stripped for direct
    * providers (e.g. `anthropic/claude-opus-4.6` → `claude-opus-4.6` on the
    * native Anthropic API); aggregators keep the full id.
    */
  def wireModel: String =
    val aggregators = Set("openrouter", "nous", "ai-gateway", "kilocode", "huggingface", "novita", "deepinfra")
    if aggregators.contains(providerSlug) then model
    else
      model.indexOf('/') match
        case -1 => model
        case i =>
          val prefix = model.take(i)
          if profile.exists(p => p.slugAndAliases.contains(prefix)) || prefix == providerSlug then model.drop(i + 1)
          else model

/** CLI-level overrides that beat everything in the config file. */
final case class RuntimeOverrides(
    model: Maybe[String] = Absent,
    provider: Maybe[String] = Absent,
    apiKey: Maybe[String] = Absent,
    reasoning: Maybe[String] = Absent
)

enum ResolveError:
  case NoCredentials(hint: String)
  case UnknownProvider(slug: String)
  case Unsupported(slug: String, reason: String)
  case NoModel(slug: String)
  case KeyCommandFailed(command: String, detail: String)

object ResolveError:
  extension (e: ResolveError)
    def message: String = e match
      case ResolveError.NoCredentials(hint)      => s"no provider credentials found. $hint"
      case ResolveError.UnknownProvider(slug)    => s"unknown provider: '$slug'"
      case ResolveError.Unsupported(slug, why)   => s"provider '$slug' is not supported by apollo: $why"
      case ResolveError.NoModel(slug)            => s"no model configured for provider '$slug' — set model.default or pass --model"
      case ResolveError.KeyCommandFailed(c, d)   => s"key_cmd '$c' failed: $d"

/** Provider/model resolution with the upstream harness precedence:
  * CLI flag > config.yaml > `APOLLO_INFERENCE_*` (or legacy
  * env > auto-detect.
  */
object Runtime:

  /** the upstream harness's silent default when an aggregator is configured but no model
    * was ever chosen (`PREFERRED_SILENT_DEFAULT_MODEL`).
    */
  private val silentDefaultModel     = "z-ai/glm-5.2"
  private val silentDefaultProviders = Set("nous", "openrouter")

  def resolve(
      config: ApolloConfig,
      overrides: RuntimeOverrides
  ): ResolvedRuntime < (Sync & Async & Abort[ResolveError]) =
    val env = config.env

    // Model aliases are checked before anything else (they may carry their
    // own provider/base_url/credential).
    val aliasHit =
      overrides.model.orElse(config.modelDefault).flatMap(m => Maybe.fromOption(config.modelAliases.get(m)))

    aliasHit match
      case Present(alias) =>
        val slug = alias.provider.getOrElse("custom")
        resolveForSlug(config, overrides, slug, Present(alias.model), alias.baseUrl,
          alias.apiKey.orElse(alias.keyEnv.flatMap(env.get)))
      case Absent =>
        val requested =
          overrides.provider
            .orElse(config.modelProvider)
            .orElse(env.get("APOLLO_INFERENCE_PROVIDER"))
            .getOrElse("auto")
        val slug =
          if requested == "auto" then autoDetect(config)
          else Result.succeed(requested)
        slug match
          case Result.Success(s) => resolveForSlug(config, overrides, s, Absent, Absent, Absent)
          case Result.Failure(e) => Abort.fail(e)
          case _                 => Abort.fail(ResolveError.NoCredentials(""))
  end resolve

  private def autoDetect(config: ApolloConfig): Result[ResolveError, String] =
    val env = config.env
    // Base-URL-configured custom endpoint counts as a credential.
    val custom =
      if config.modelProvider.contains("custom") || (config.modelBaseUrl.nonEmpty && config.modelProvider.isEmpty)
      then config.modelBaseUrl.map(_ => "custom")
      else Absent
    custom match
      case Present(s) => Result.succeed(s)
      case Absent =>
        Profiles.autoDetectOrder
          .find(slug => Profiles.find(slug).exists(p => p.keyEnvVars.exists(v => env.get(v).nonEmpty)))
          .map(Result.succeed)
          .getOrElse(
            Result.fail(ResolveError.NoCredentials(
              "Set an API key (e.g. OPENROUTER_API_KEY or ANTHROPIC_API_KEY) in the environment or ~/.apollo/.env, or run `apollo setup`."
            ))
          )

  private def resolveForSlug(
      config: ApolloConfig,
      overrides: RuntimeOverrides,
      rawSlug: String,
      aliasModel: Maybe[String],
      aliasBaseUrl: Maybe[String],
      aliasKey: Maybe[String]
  ): ResolvedRuntime < (Sync & Async & Abort[ResolveError]) =
    val env  = config.env
    val slug = rawSlug.trim.toLowerCase

    // `custom:<name>` → a named entry of the `providers:` map.
    if slug.startsWith("custom:") then
      val key = slug.drop("custom:".length)
      config.namedProviders.find(_.key == key) match
        case Some(cp) => resolveCustomNamed(config, overrides, cp, aliasModel)
        case None     => Abort.fail(ResolveError.UnknownProvider(slug))
    else
      Profiles.find(slug) match
        case Absent => Abort.fail(ResolveError.UnknownProvider(slug))
        case Present(profile) if profile.unsupported =>
          Abort.fail(ResolveError.Unsupported(profile.name, profile.unsupportedReason))
        case Present(profile) if profile.name == "copilot" =>
          resolveCopilot(config, overrides, profile, aliasModel, aliasKey)
        case Present(profile) =>
          val baseUrl =
            aliasBaseUrl
              .orElse(profile.baseUrlEnvVars.foldLeft(Maybe.empty[String])((acc, v) => acc.orElse(env.get(v))))
              // `model.base_url` applies to the custom profile, or to any profile
              // the config explicitly names (parity with the api-key gating).
              .orElse(config.modelBaseUrl.filter(_ =>
                profile.name == "custom" || config.modelProvider.exists(p => sameProvider(p, slug))))
              .getOrElse(profile.baseUrl)
          val staticKey =
            aliasKey
              .orElse(config.modelApiKey.filter(_ => config.modelProvider.forall(p => sameProvider(p, slug))))
              .orElse(profile.keyEnvVars.foldLeft(Maybe.empty[String])((acc, v) => acc.orElse(env.get(v))))
          val model =
            overrides.model
              .orElse(aliasModel)
              .orElse(config.modelDefault.filter(_ => config.modelProvider.forall(p => sameProvider(p, slug))))
              .orElse(env.get("APOLLO_INFERENCE_MODEL"))
              .orElse(defaultModel(profile))
          model match
            case Absent => Abort.fail(ResolveError.NoModel(profile.name))
            case Present(m) =>
              val apiMode = resolveApiMode(config, profile, baseUrl, m)
              // Credential precedence: explicit --api-key > model.key_cmd
              // (short-lived OAuth-CLI token) > static keys (config/env/profile).
              val keyEff: Maybe[String] < (Sync & Async & Abort[ResolveError]) =
                overrides.apiKey match
                  case Present(k) => Present(k)
                  case Absent => config.modelKeyCmd match
                    case Present(cmd) => KeyCommand.run(cmd).map(Present(_))
                    case Absent       => staticKey
              keyEff.map(apiKey =>
                assemble(config, overrides, profile.name, profile.displayName, m,
                  baseUrl, apiKey, apiMode, profile.defaultHeaders, Present(profile)))
  end resolveForSlug

  /** Copilot: obtain a GitHub token (stored login, then env), exchange it for a
    * short-lived Copilot bearer, and assemble a ChatCompletions runtime with the
    * Copilot headers. The bearer is minted per resolve (session start); a very
    * long session may outlive it and need a re-login. */
  private def resolveCopilot(
      config: ApolloConfig,
      overrides: RuntimeOverrides,
      profile: Profile,
      aliasModel: Maybe[String],
      aliasKey: Maybe[String]
  ): ResolvedRuntime < (Sync & Async & Abort[ResolveError]) =
    val env = config.env
    CopilotAuth.loadGithubToken(config.paths).map { stored =>
      val ghToken = overrides.apiKey.orElse(aliasKey).orElse(stored)
        .orElse(profile.keyEnvVars.foldLeft(Maybe.empty[String])((a, v) => a.orElse(env.get(v))))
      val model = overrides.model.orElse(aliasModel)
        .orElse(env.get("APOLLO_INFERENCE_MODEL")).orElse(defaultModel(profile))
      (ghToken, model) match
        case (Absent, _) =>
          Abort.fail(ResolveError.NoCredentials("Run `apollo auth copilot login`, or set GH_TOKEN/GITHUB_TOKEN."))
        case (_, Absent) => Abort.fail(ResolveError.NoModel(profile.name))
        case (Present(gh), Present(m)) =>
          CopilotAuth.exchange(gh).map {
            case Result.Success(tok) =>
              Result.succeed(assemble(config, overrides, profile.name, profile.displayName, m,
                profile.baseUrl, Present(tok.token), ApiMode.ChatCompletions,
                profile.defaultHeaders ++ CopilotAuth.headers, Present(profile)))
            case Result.Failure(err) =>
              Result.fail(ResolveError.KeyCommandFailed("copilot token-exchange", err))
          }.map(Abort.get)
    }

  private def resolveCustomNamed(
      config: ApolloConfig,
      overrides: RuntimeOverrides,
      cp: CustomProvider,
      aliasModel: Maybe[String]
  ): ResolvedRuntime < (Sync & Async & Abort[ResolveError]) =
    val env     = config.env
    val baseUrl = cp.baseUrl.getOrElse("")
    val keyFromCmd: Maybe[String] < (Sync & Async & Abort[ResolveError]) =
      cp.keyCmd match
        case Present(cmd) => KeyCommand.run(cmd).map(Present(_))
        case Absent       => Absent

    keyFromCmd.map { cmdKey =>
      val apiKey = overrides.apiKey
        .orElse(cmdKey)
        .orElse(cp.apiKey)
        .orElse(cp.keyEnv.flatMap(env.get))
      val model = overrides.model.orElse(aliasModel).orElse(cp.model).orElse(config.modelDefault)
      model match
        case Absent => Abort.fail(ResolveError.NoModel(cp.slug))
        case Present(m) =>
          val apiMode = ApiMode.hostMandated(baseUrl)
            .orElse(cp.apiMode.flatMap(ApiMode.parse))
            .getOrElse(ApiMode.ChatCompletions)
          assemble(config, overrides, cp.slug, cp.slug, m, baseUrl, apiKey, apiMode, cp.extraHeaders,
            Profiles.find("custom"))
    }
  end resolveCustomNamed

  /** the upstream harness api-mode resolution: host-mandated > per-model (Nous dual wire)
    * > persisted `model.api_mode` (only when the provider matches) > profile.
    */
  private def resolveApiMode(config: ApolloConfig, profile: Profile, baseUrl: String, model: String): ApiMode =
    ApiMode.hostMandated(baseUrl).getOrElse {
      if profile.name == "nous" then
        if model.startsWith("anthropic/") then ApiMode.AnthropicMessages else ApiMode.ChatCompletions
      else
        config.modelApiMode
          .filter(_ => config.modelProvider.exists(p => sameProvider(p, profile.name)))
          .flatMap(ApiMode.parse)
          .getOrElse(profile.apiMode)
    }

  private def assemble(
      config: ApolloConfig,
      overrides: RuntimeOverrides,
      slug: String,
      displayName: String,
      model: String,
      baseUrl: String,
      apiKey: Maybe[String],
      apiMode: ApiMode,
      profileHeaders: Map[String, String],
      profile: Maybe[Profile]
  ): ResolvedRuntime =
    ResolvedRuntime(
      providerSlug = slug,
      displayName = if displayName.nonEmpty then displayName else slug,
      model = model,
      baseUrl = baseUrl.stripSuffix("/"),
      apiKey = apiKey,
      apiMode = apiMode,
      // Bedrock's two-part AWS creds + region don't fit the single `apiKey`
      // field, so they ride in `headers` under private keys the SigV4
      // transport consumes and strips (never sent as real headers).
      headers = profileHeaders ++ config.modelDefaultHeaders ++
        (if apiMode == ApiMode.BedrockConverse then awsCreds(config) else Map.empty),
      profile = profile,
      reasoning = resolveReasoning(config, overrides, model, slug),
      maxTokens = config.modelMaxTokens.orElse(profile.flatMap(_.defaultMaxTokens)),
      contextLength = config.contextLength,
      streaming = config.modelStreaming
    )

  /** `agent.reasoning_effort` + per-model `agent.reasoning_overrides`
    * (first match wins; dots and dashes interchangeable; provider prefix
    * optional), then the CLI `--reasoning` flag on top.
    */
  private def resolveReasoning(
      config: ApolloConfig,
      overrides: RuntimeOverrides,
      model: String,
      slug: String
  ): Maybe[Reasoning.Config] =
    def normalize(s: String) = s.toLowerCase.replace('.', '-')
    val modelKeys = List(normalize(model), normalize(s"$slug/$model"), normalize(model.split('/').last))
    val fromOverride = config.reasoningOverrides.collectFirst {
      case (k, v) if modelKeys.exists(mk => mk == normalize(k) || mk.endsWith("/" + normalize(k))) => v
    }
    val effective = overrides.reasoning.orElse(Maybe.fromOption(fromOverride)).getOrElse(config.reasoningEffort)
    Reasoning.fromConfigValue(effective)

  private def defaultModel(profile: Profile): Maybe[String] =
    if silentDefaultProviders.contains(profile.name) then Present(silentDefaultModel)
    else Maybe.fromOption(profile.staticModels.headOption)

  /** AWS credentials + region for Bedrock, pulled from the env chain (which
    * layers `~/.apollo/.env` over the process env) into the private header
    * slots the SigV4 transport reads.
    */
  private def awsCreds(config: ApolloConfig): Map[String, String] =
    val env    = config.env
    val region = env.get("AWS_REGION").orElse(env.get("AWS_DEFAULT_REGION"))
      .getOrElse("us-east-1")
    List(
      BedrockTransport.hAccessKey   -> env.get("AWS_ACCESS_KEY_ID"),
      BedrockTransport.hSecretKey   -> env.get("AWS_SECRET_ACCESS_KEY"),
      BedrockTransport.hSessionTok  -> env.get("AWS_SESSION_TOKEN"),
      BedrockTransport.hRegion      -> Present(region)
    ).collect { case (k, kyo.Present(v)) => k -> v }.toMap

  private def sameProvider(a: String, b: String): Boolean =
    val pa = Profiles.find(a)
    val pb = Profiles.find(b)
    a == b || (pa.nonEmpty && pa == pb)
end Runtime

/** `key_cmd` support: run a command that prints a token (bare, or JSON with
  * an `access_token` field), mirroring the upstream harness's command-minted credentials.
  */
object KeyCommand:

  def run(command: String): String < (Sync & Async & Abort[ResolveError]) =
    Abort.run[CommandException] {
      Command("sh", "-c", command).text
    }.map {
      case Result.Success(out) =>
        val trimmed = out.trim
        if trimmed.isEmpty then Abort.fail(ResolveError.KeyCommandFailed(command, "empty output"))
        else if trimmed.startsWith("{") then
          Jx.parse(trimmed) match
            case Result.Success(json) =>
              (json / "access_token").asStr match
                case Present(token) => token
                case Absent => Abort.fail(ResolveError.KeyCommandFailed(command, "JSON output missing access_token"))
            case _ => Abort.fail(ResolveError.KeyCommandFailed(command, "unparseable JSON output"))
        else trimmed
      case Result.Failure(e) => Abort.fail(ResolveError.KeyCommandFailed(command, e.toString))
      case Result.Panic(e)   => Abort.panic(e)
    }
end KeyCommand

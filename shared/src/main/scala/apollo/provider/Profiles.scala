package apollo.provider

import kyo.*
import ApiMode.*
import ReasoningStyle.*
import TemperaturePolicy.*

/** The bundled provider registry, mirroring the upstream harness's
  * `plugins/model-providers/` + upstream `auth.py` tables. Every the upstream harness
  * provider slug and alias resolves here; the handful that require
  * infrastructure this build doesn't ship (OAuth brokers, AWS SigV4,
  * subprocess transports, Entra ID) are present but marked `unsupported` so
  * selection produces an actionable error instead of a mystery.
  */
object Profiles:

  private val attribution = Map(
    "HTTP-Referer" -> "https://github.com/dgreco/apollo",
    "X-Title"      -> "Apollo Agent"
  )

  val all: List[Profile] = List(
    // --- Aggregators & majors -------------------------------------------
    Profile(
      name = "openrouter",
      aliases = List("or", "openai" /* legacy alias group */ ),
      displayName = "OpenRouter",
      baseUrl = "https://openrouter.ai/api/v1",
      keyEnvVars = List("OPENROUTER_API_KEY"),
      baseUrlEnvVars = List("OPENROUTER_BASE_URL"),
      defaultHeaders = attribution,
      reasoningStyle = ExtraBodyReasoning,
      supportsVision = true,
      defaultAuxModel = "google/gemini-3-flash-preview",
      staticModels = List(
        "anthropic/claude-opus-4.6",
        "anthropic/claude-sonnet-4.6",
        "openai/gpt-5.4",
        "google/gemini-3-flash-preview",
        "z-ai/glm-5.2",
        "deepseek/deepseek-v4-pro",
        "moonshotai/kimi-k3"
      )
    ),
    Profile(
      name = "anthropic",
      aliases = List("claude", "claude-oauth", "claude-code"),
      displayName = "Anthropic",
      baseUrl = "https://api.anthropic.com",
      apiMode = AnthropicMessages,
      keyEnvVars = List("ANTHROPIC_API_KEY", "ANTHROPIC_TOKEN", "CLAUDE_CODE_OAUTH_TOKEN"),
      reasoningStyle = AnthropicNative,
      supportsVision = true,
      defaultAuxModel = "claude-haiku-4-5-20251001",
      staticModels = List(
        "claude-opus-4-6",
        "claude-sonnet-4-6",
        "claude-haiku-4-5-20251001"
      )
    ),
    Profile(
      name = "openai-api",
      aliases = List("openai-direct"),
      displayName = "OpenAI",
      baseUrl = "https://api.openai.com/v1",
      apiMode = CodexResponses, // host-mandated
      keyEnvVars = List("OPENAI_API_KEY"),
      baseUrlEnvVars = List("OPENAI_BASE_URL"),
      reasoningStyle = ResponsesNative,
      supportsVision = true,
      staticModels = List("gpt-5.4", "gpt-5.4-mini", "gpt-5.3-codex")
    ),
    Profile(
      name = "nous",
      aliases = List("nous-portal", "nousresearch", "nous-api"),
      displayName = "Nous Portal",
      baseUrl = "https://inference-api.nousresearch.com/v1",
      keyEnvVars = List("NOUS_API_KEY"),
      baseUrlEnvVars = List("NOUS_BASE_URL"),
      reasoningStyle = ExtraBodyReasoning,
      defaultAuxModel = "gemini-3-flash",
      staticModels = List("hermes-4-405b", "hermes-4-70b", "anthropic/claude-sonnet-4.6")
    ),
    Profile(
      name = "gemini",
      aliases = List("google", "google-gemini", "google-ai-studio"),
      displayName = "Google AI Studio",
      // The OpenAI-compat subpath: chat-completions wire without a native SDK.
      baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
      keyEnvVars = List("GOOGLE_API_KEY", "GEMINI_API_KEY"),
      baseUrlEnvVars = List("GEMINI_BASE_URL"),
      reasoningStyle = NoReasoning, // thinking_config quirks handled per-model
      supportsVision = true,
      staticModels = List("gemini-3-flash-preview", "gemini-3-pro-preview", "gemini-2.5-flash")
    ),
    // --- OpenAI-compatible fleet ----------------------------------------
    Profile(
      name = "ai-gateway",
      aliases = List("vercel", "vercel-ai-gateway", "ai_gateway", "aigateway"),
      displayName = "Vercel AI Gateway",
      baseUrl = "https://ai-gateway.vercel.sh/v1",
      keyEnvVars = List("AI_GATEWAY_API_KEY"),
      defaultHeaders = attribution,
      reasoningStyle = ExtraBodyReasoning,
      defaultAuxModel = "google/gemini-3-flash"
    ),
    Profile(
      name = "zai",
      aliases = List("glm", "z-ai", "z.ai", "zhipu"),
      displayName = "Z.AI (GLM)",
      baseUrl = "https://api.z.ai/api/paas/v4",
      keyEnvVars = List("GLM_API_KEY", "ZAI_API_KEY", "Z_AI_API_KEY"),
      baseUrlEnvVars = List("GLM_BASE_URL"),
      reasoningStyle = TopLevelEffort(Reasoning.glm53._1, Reasoning.glm53._2),
      staticModels = List("glm-5.3", "glm-5.2", "glm-4.7")
    ),
    Profile(
      name = "kimi-coding",
      aliases = List("kimi", "moonshot", "kimi-for-coding"),
      displayName = "Kimi / Moonshot",
      baseUrl = "https://api.moonshot.ai/v1",
      keyEnvVars = List("KIMI_API_KEY", "KIMI_CODING_API_KEY"),
      baseUrlEnvVars = List("KIMI_BASE_URL"),
      defaultHeaders = attribution,
      temperature = Omit,
      defaultMaxTokens = Present(32000),
      reasoningStyle = TopLevelEffort(Reasoning.kimiK3._1, Reasoning.kimiK3._2),
      staticModels = List("kimi-k3", "kimi-k2.5")
    ),
    Profile(
      name = "kimi-coding-cn",
      aliases = List("kimi-cn", "moonshot-cn"),
      displayName = "Kimi / Moonshot (China)",
      baseUrl = "https://api.moonshot.cn/v1",
      keyEnvVars = List("KIMI_CN_API_KEY"),
      temperature = Omit,
      defaultMaxTokens = Present(32000),
      reasoningStyle = TopLevelEffort(Reasoning.kimiK3._1, Reasoning.kimiK3._2)
    ),
    Profile(
      name = "minimax",
      aliases = List("mini-max"),
      displayName = "MiniMax",
      baseUrl = "https://api.minimax.io/anthropic",
      apiMode = AnthropicMessages,
      keyEnvVars = List("MINIMAX_API_KEY"),
      baseUrlEnvVars = List("MINIMAX_BASE_URL"),
      reasoningStyle = AnthropicNative,
      staticModels = List("minimax-m3", "minimax-m2.5")
    ),
    Profile(
      name = "minimax-cn",
      aliases = List("minimax-china", "minimax_cn"),
      displayName = "MiniMax (China)",
      baseUrl = "https://api.minimaxi.com/anthropic",
      apiMode = AnthropicMessages,
      keyEnvVars = List("MINIMAX_CN_API_KEY"),
      reasoningStyle = AnthropicNative
    ),
    Profile(
      name = "deepseek",
      aliases = List("deepseek-chat"),
      displayName = "DeepSeek",
      baseUrl = "https://api.deepseek.com/v1",
      keyEnvVars = List("DEEPSEEK_API_KEY"),
      baseUrlEnvVars = List("DEEPSEEK_BASE_URL"),
      reasoningStyle = TopLevelEffort(Reasoning.deepseekV4._1, Reasoning.deepseekV4._2),
      staticModels = List("deepseek-v4-pro", "deepseek-v4-flash")
    ),
    Profile(
      name = "huggingface",
      aliases = List("hf", "hugging-face", "huggingface-hub"),
      displayName = "Hugging Face",
      baseUrl = "https://router.huggingface.co/v1",
      keyEnvVars = List("HF_TOKEN"),
      baseUrlEnvVars = List("HF_BASE_URL")
    ),
    Profile(
      name = "nvidia",
      aliases = List("nvidia-nim"),
      displayName = "NVIDIA NIM",
      baseUrl = "https://integrate.api.nvidia.com/v1",
      keyEnvVars = List("NVIDIA_API_KEY"),
      baseUrlEnvVars = List("NVIDIA_BASE_URL"),
      defaultMaxTokens = Present(16384)
    ),
    Profile(
      name = "xiaomi",
      aliases = List("mimo", "xiaomi-mimo"),
      displayName = "Xiaomi MiMo",
      baseUrl = "https://api.xiaomimimo.com/v1",
      keyEnvVars = List("XIAOMI_API_KEY"),
      baseUrlEnvVars = List("XIAOMI_BASE_URL"),
      supportsVision = true
    ),
    Profile(
      name = "arcee",
      aliases = List("arcee-ai", "arceeai"),
      displayName = "Arcee AI",
      baseUrl = "https://api.arcee.ai/api/v1",
      keyEnvVars = List("ARCEEAI_API_KEY"),
      baseUrlEnvVars = List("ARCEE_BASE_URL")
    ),
    Profile(
      name = "ollama-cloud",
      aliases = List("ollama_cloud"),
      displayName = "Ollama Cloud",
      baseUrl = "https://ollama.com/v1",
      keyEnvVars = List("OLLAMA_API_KEY"),
      baseUrlEnvVars = List("OLLAMA_BASE_URL"),
      reasoningStyle = TopLevelEffort(Reasoning.ollamaCloud._1, Reasoning.ollamaCloud._2),
      staticModels = List("qwen3.5:397b", "glm-4.7", "deepseek-v4")
    ),
    Profile(
      name = "deepinfra",
      aliases = List("deep-infra", "deepinfra-ai"),
      displayName = "DeepInfra",
      baseUrl = "https://api.deepinfra.com/v1/openai",
      keyEnvVars = List("DEEPINFRA_API_KEY"),
      baseUrlEnvVars = List("DEEPINFRA_BASE_URL"),
      defaultAuxModel = "deepseek-ai/DeepSeek-V4-Flash"
    ),
    Profile(
      name = "kilocode",
      aliases = List("kilo-code", "kilo", "kilo-gateway"),
      displayName = "KiloCode",
      baseUrl = "https://api.kilo.ai/api/gateway",
      keyEnvVars = List("KILOCODE_API_KEY"),
      baseUrlEnvVars = List("KILOCODE_BASE_URL"),
      defaultAuxModel = "google/gemini-3.6-flash"
    ),
    Profile(
      name = "fireworks",
      aliases = List("fireworks-ai", "fw"),
      displayName = "Fireworks AI",
      baseUrl = "https://api.fireworks.ai/inference/v1",
      keyEnvVars = List("FIREWORKS_API_KEY"),
      defaultHeaders = attribution
    ),
    Profile(
      name = "gmi",
      aliases = List("gmi-cloud", "gmicloud"),
      displayName = "GMI Cloud",
      baseUrl = "https://api.gmi-serving.com/v1",
      keyEnvVars = List("GMI_API_KEY"),
      baseUrlEnvVars = List("GMI_BASE_URL")
    ),
    Profile(
      name = "novita",
      aliases = List("novita-ai", "novitaai"),
      displayName = "Novita AI",
      baseUrl = "https://api.novita.ai/openai/v1",
      keyEnvVars = List("NOVITA_API_KEY"),
      baseUrlEnvVars = List("NOVITA_BASE_URL")
    ),
    Profile(
      name = "upstage",
      aliases = List("solar"),
      displayName = "Upstage Solar",
      baseUrl = "https://api.upstage.ai/v1",
      keyEnvVars = List("UPSTAGE_API_KEY"),
      baseUrlEnvVars = List("UPSTAGE_BASE_URL"),
      reasoningStyle = TopLevelEffort(Reasoning.threeLevels._1, Reasoning.threeLevels._2)
    ),
    Profile(
      name = "stepfun",
      aliases = List("step", "stepfun-coding-plan"),
      displayName = "StepFun",
      baseUrl = "https://api.stepfun.ai/step_plan/v1",
      keyEnvVars = List("STEPFUN_API_KEY"),
      baseUrlEnvVars = List("STEPFUN_BASE_URL")
    ),
    Profile(
      name = "nebius-token-factory",
      aliases = List("nebius", "nebius-tokenfactory", "nebius-tf", "token-factory", "tokenfactory"),
      displayName = "Nebius Token Factory",
      baseUrl = "https://api.tokenfactory.nebius.com/v1",
      keyEnvVars = List("NEBIUS_API_KEY", "NEBIUS_TOKEN_FACTORY_API_KEY"),
      baseUrlEnvVars = List("NEBIUS_BASE_URL"),
      reasoningStyle = TopLevelEffort(Reasoning.threeLevels._1, Reasoning.threeLevels._2)
    ),
    Profile(
      name = "alibaba",
      aliases = List("dashscope", "alibaba-cloud", "qwen-dashscope"),
      displayName = "Alibaba DashScope",
      baseUrl = "https://dashscope-intl.aliyuncs.com/compatible-mode/v1",
      keyEnvVars = List("DASHSCOPE_API_KEY"),
      baseUrlEnvVars = List("DASHSCOPE_BASE_URL"),
      staticModels = List("qwen3.5-max", "qwen3.5-coder")
    ),
    Profile(
      name = "alibaba-cn",
      aliases = List("dashscope-cn", "alibaba-cloud-cn"),
      displayName = "Alibaba DashScope (China)",
      baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
      keyEnvVars = List("DASHSCOPE_API_KEY"),
      baseUrlEnvVars = List("DASHSCOPE_CN_BASE_URL")
    ),
    Profile(
      name = "commandcode",
      aliases = List("commandcode-chat"),
      displayName = "CommandCode",
      baseUrl = "https://api.commandcode.ai/provider/v1",
      keyEnvVars = List("COMMANDCODE_API_KEY"),
      baseUrlEnvVars = List("COMMANDCODE_BASE_URL")
    ),
    Profile(
      name = "commandcode-anthropic",
      aliases = List("commandcode-claude"),
      displayName = "CommandCode (Anthropic wire)",
      baseUrl = "https://api.commandcode.ai/provider/v1",
      apiMode = AnthropicMessages,
      keyEnvVars = List("COMMANDCODE_API_KEY"),
      baseUrlEnvVars = List("COMMANDCODE_ANTHROPIC_BASE_URL"),
      reasoningStyle = AnthropicNative
    ),
    Profile(
      name = "tencent-tokenhub",
      aliases = List("tokenhub"),
      displayName = "Tencent TokenHub",
      baseUrl = "https://tokenhub.tencentmaas.com/v1",
      keyEnvVars = List("TOKENHUB_API_KEY"),
      baseUrlEnvVars = List("TOKENHUB_BASE_URL"),
      reasoningStyle = TopLevelEffort(Reasoning.threeLevels._1, Reasoning.threeLevels._2)
    ),
    Profile(
      name = "tencent-tokenplan",
      aliases = List("tokenplan"),
      displayName = "Tencent TokenPlan",
      baseUrl = "https://api.lkeap.cloud.tencent.com/plan/anthropic",
      apiMode = AnthropicMessages,
      keyEnvVars = List("TOKENPLAN_API_KEY"),
      baseUrlEnvVars = List("TOKENPLAN_BASE_URL"),
      reasoningStyle = AnthropicNative
    ),
    Profile(
      name = "opencode-zen",
      aliases = List("opencode", "opencode_zen", "zen"),
      displayName = "OpenCode Zen",
      baseUrl = "https://opencode.ai/zen/v1",
      keyEnvVars = List("OPENCODE_ZEN_API_KEY"),
      baseUrlEnvVars = List("OPENCODE_ZEN_BASE_URL"),
      defaultHeaders = attribution
    ),
    Profile(
      name = "opencode-go",
      aliases = List("opencode_go", "go", "opencode-go-sub"),
      displayName = "OpenCode Go",
      baseUrl = "https://opencode.ai/zen/go/v1",
      keyEnvVars = List("OPENCODE_GO_API_KEY"),
      baseUrlEnvVars = List("OPENCODE_GO_BASE_URL"),
      defaultHeaders = attribution
    ),
    Profile(
      name = "opencode-free",
      aliases = List("free", "opencode_free"),
      displayName = "OpenCode Free",
      baseUrl = "https://opencode.ai/zen/v1",
      defaultHeaders = attribution,
      defaultAuxModel = "laguna-s-2.1-free"
    ),
    Profile(
      name = "meta-ai",
      aliases = List("meta", "muse", "muse-spark", "model-api", "msl"),
      displayName = "Meta AI",
      baseUrl = "https://api.meta.ai/v1",
      apiMode = CodexResponses,
      keyEnvVars = List("MODEL_API_KEY", "META_API_KEY", "META_MODEL_API_KEY"),
      baseUrlEnvVars = List("META_BASE_URL"),
      defaultMaxTokens = Present(16384),
      reasoningStyle = TopLevelEffort(Reasoning.metaAi._1, Reasoning.metaAi._2),
      supportsVision = true
    ),
    Profile(
      name = "xai",
      aliases = List("grok", "x-ai", "x.ai"),
      displayName = "xAI",
      baseUrl = "https://api.x.ai/v1",
      apiMode = CodexResponses,
      keyEnvVars = List("XAI_API_KEY"),
      baseUrlEnvVars = List("XAI_BASE_URL"),
      defaultHeaders = Map("User-Agent" -> "Apollo/0.1"),
      reasoningStyle = ResponsesNative,
      staticModels = List("grok-4.6", "grok-4.6-mini")
    ),
    Profile(
      name = "router",
      aliases = List("ramp-router", "ramp", "router.com"),
      displayName = "Router",
      baseUrl = "https://api.router.com/v1",
      apiMode = CodexResponses,
      keyEnvVars = List("RAMP_ROUTER_API_KEY", "ROUTER_API_KEY"),
      baseUrlEnvVars = List("RAMP_ROUTER_BASE_URL"),
      defaultHeaders = Map("User-Agent" -> "Apollo/0.1"),
      reasoningStyle = ResponsesNative,
      supportsVision = true
    ),
    Profile(
      name = "actual",
      aliases = List("actual-computer", "actualcomputer", "aci"),
      displayName = "Actual",
      baseUrl = "https://api.actual.inc/v1",
      apiMode = CodexResponses,
      keyEnvVars = List("ACTUAL_API_KEY"),
      baseUrlEnvVars = List("ACTUAL_BASE_URL"),
      reasoningStyle = ResponsesNative
    ),
    Profile(
      name = "lmstudio",
      aliases = List("lm-studio"),
      displayName = "LM Studio",
      baseUrl = "http://127.0.0.1:1234/v1",
      keyEnvVars = List("LM_API_KEY"),
      baseUrlEnvVars = List("LM_BASE_URL"),
      reasoningStyle = TopLevelEffort(Reasoning.openAiCompatWireEfforts.toSeq, Map.empty)
    ),
    Profile(
      name = "custom",
      aliases = List("ollama", "local", "vllm", "llamacpp", "llama.cpp", "llama-cpp"),
      displayName = "Custom endpoint",
      baseUrl = "",
      defaultMaxTokens = Present(65536),
      reasoningStyle = TopLevelEffort(Reasoning.openAiCompatWireEfforts.toSeq, Map.empty)
    ),
    // --- Unsupported-in-this-build auth mechanisms ----------------------
    Profile(
      name = "openai-codex",
      aliases = List("codex", "openai_codex"),
      displayName = "OpenAI Codex (ChatGPT OAuth)",
      baseUrl = "https://chatgpt.com/backend-api/codex",
      apiMode = CodexResponses,
      reasoningStyle = ResponsesNative,
      unsupported = true,
      unsupportedReason = "requires the ChatGPT OAuth broker, not implemented in apollo"
    ),
    Profile(
      name = "copilot",
      aliases = List("github-copilot", "github-models", "github-model", "github"),
      displayName = "GitHub Copilot",
      baseUrl = "https://api.githubcopilot.com",
      // The GitHub token (from `apollo auth copilot login`, or one of these env
      // vars) is exchanged for a short-lived Copilot bearer at resolve time.
      keyEnvVars = List("COPILOT_GITHUB_TOKEN", "GH_TOKEN", "GITHUB_TOKEN")
    ),
    Profile(
      name = "bedrock",
      aliases = List("aws", "aws-bedrock", "amazon-bedrock", "amazon"),
      displayName = "AWS Bedrock",
      baseUrl = "https://bedrock-runtime.us-east-1.amazonaws.com",
      apiMode = BedrockConverse,
      // AWS creds gate auto-detect; the SigV4 signer reads them from the env
      // chain during resolution. Bedrock has no single API key.
      keyEnvVars = List("AWS_ACCESS_KEY_ID", "AWS_BEARER_TOKEN_BEDROCK"),
      staticModels = List(
        "anthropic.claude-sonnet-4-5-20250929-v1:0",
        "anthropic.claude-opus-4-1-20250805-v1:0",
        "anthropic.claude-3-5-sonnet-20241022-v2:0"
      )
    ),
    Profile(
      name = "vertex",
      aliases = List("google-vertex", "vertex-ai", "gcp-vertex"),
      displayName = "Google Vertex AI",
      unsupported = true,
      unsupportedReason = "requires GCP service-account OAuth2 minting, not implemented in apollo"
    ),
    Profile(
      name = "azure-foundry",
      aliases = List("azure", "azure-ai-foundry", "azure-ai"),
      displayName = "Azure Foundry",
      keyEnvVars = List("AZURE_FOUNDRY_API_KEY"),
      baseUrlEnvVars = List("AZURE_FOUNDRY_BASE_URL")
      // API-key mode works over the OpenAI-compatible wire; Entra ID does not.
    ),
    Profile(
      name = "qwen-oauth",
      aliases = List("qwen", "qwen-portal", "qwen-cli"),
      displayName = "Qwen Portal",
      baseUrl = "https://portal.qwen.ai/v1",
      keyEnvVars = List("QWEN_API_KEY"),
      defaultMaxTokens = Present(65536)
    ),
    Profile(
      name = "minimax-oauth",
      aliases = List("minimax_oauth", "minimax-oauth-io"),
      displayName = "MiniMax (OAuth)",
      baseUrl = "https://api.minimax.io/anthropic",
      apiMode = AnthropicMessages,
      reasoningStyle = AnthropicNative,
      unsupported = true,
      unsupportedReason = "requires the external OAuth flow; use `minimax` with MINIMAX_API_KEY instead"
    ),
    // --- models.dev-only slugs (plain OpenAI-compatible) ----------------
    Profile(
      name = "groq",
      displayName = "Groq",
      baseUrl = "https://api.groq.com/openai/v1",
      keyEnvVars = List("GROQ_API_KEY")
    ),
    Profile(
      name = "mistral",
      displayName = "Mistral",
      baseUrl = "https://api.mistral.ai/v1",
      keyEnvVars = List("MISTRAL_API_KEY")
    ),
    Profile(
      name = "togetherai",
      aliases = List("together"),
      displayName = "Together AI",
      baseUrl = "https://api.together.xyz/v1",
      keyEnvVars = List("TOGETHER_API_KEY", "TOGETHERAI_API_KEY")
    ),
    Profile(
      name = "cohere",
      displayName = "Cohere",
      baseUrl = "https://api.cohere.ai/compatibility/v1",
      keyEnvVars = List("COHERE_API_KEY", "CO_API_KEY")
    ),
    Profile(
      name = "perplexity",
      displayName = "Perplexity",
      baseUrl = "https://api.perplexity.ai",
      keyEnvVars = List("PERPLEXITY_API_KEY", "PPLX_API_KEY")
    )
  )

  private val bySlug: Map[String, Profile] =
    all.flatMap(p => p.slugAndAliases.map(_ -> p)).toMap

  def find(slug: String): Maybe[Profile] =
    Maybe.fromOption(bySlug.get(slug.trim.toLowerCase))

  /** Auto-detection scan order, mirroring the upstream harness's `PROVIDER_REGISTRY`
    * priority: first provider whose key env var is set wins.
    */
  val autoDetectOrder: List[String] = List(
    "openrouter", "nous", "anthropic", "openai-api", "gemini", "zai",
    "kimi-coding", "minimax", "deepseek", "xai", "groq", "mistral",
    "huggingface", "nvidia", "deepinfra", "fireworks", "ai-gateway",
    "kilocode", "ollama-cloud", "togetherai", "xiaomi", "arcee",
    "upstage", "novita", "stepfun", "alibaba", "azure-foundry"
  )
end Profiles

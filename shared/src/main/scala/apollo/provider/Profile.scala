// SPDX-FileCopyrightText: 2026 David Greco <greco@acm.org>
//
// SPDX-License-Identifier: Apache-2.0

package apollo.provider

import kyo.*

/** The wire protocol a provider endpoint speaks. */
enum ApiMode:
  case ChatCompletions, AnthropicMessages, CodexResponses, BedrockConverse

object ApiMode:
  /** Config-file spellings accepted by the upstream harness (`_API_MODE_ALIASES`). */
  def parse(value: String): Maybe[ApiMode] =
    value.trim.toLowerCase.replace('-', '_') match
      case "chat_completions" | "openai" | "openai_chat" | "chatcompletions" => Present(ChatCompletions)
      case "codex_responses" | "responses" | "openai_responses"              => Present(CodexResponses)
      case "anthropic_messages" | "anthropic" | "messages"                   => Present(AnthropicMessages)
      case "bedrock_converse" | "bedrock"                                    => Present(BedrockConverse)
      case _                                                                 => Absent

  /** Hosts that mandate a wire protocol regardless of configuration; matching
    * is exact-host or dot-suffix, never substring (upstream issue #32243).
    */
  def hostMandated(baseUrl: String): Maybe[ApiMode] =
    val (host, path) = splitHostPath(baseUrl)
    def hostIs(h: String)     = host == h
    def hostSuffix(s: String) = host == s || host.endsWith("." + s)
    if hostIs("api.anthropic.com") then Present(AnthropicMessages)
    else if path.endsWith("/anthropic") || path.endsWith("/anthropic/v1") then Present(AnthropicMessages)
    else if hostIs("api.kimi.com") && path.contains("/coding") then Present(AnthropicMessages)
    else if hostIs("api.x.ai") || hostIs("api.meta.ai") || hostIs("api.actual.inc") || hostIs("api.router.com")
    then Present(CodexResponses)
    else if hostIs("api.openai.com") || hostIs("us.api.openai.com") || hostIs("eu.api.openai.com")
    then Present(CodexResponses)
    else if host.startsWith("bedrock-runtime.") && hostSuffix("amazonaws.com") then Present(BedrockConverse)
    else Absent

  private def splitHostPath(url: String): (String, String) =
    val noScheme = url.indexOf("://") match
      case -1 => url
      case i  => url.drop(i + 3)
    noScheme.indexOf('/') match
      case -1 => (noScheme.toLowerCase, "")
      case i  => (noScheme.take(i).toLowerCase, noScheme.drop(i).stripSuffix("/"))
end ApiMode

/** How the provider takes the reasoning-effort ask on the wire. */
enum ReasoningStyle:
  /** `extra_body.reasoning = {enabled, effort}` (OpenRouter, Nous, Vercel). */
  case ExtraBodyReasoning
  /** Top-level `reasoning_effort` clamped to a vocabulary. */
  case TopLevelEffort(supported: Seq[String], overrides: Map[String, String])
  /** `extra_body.thinking = {type: enabled|disabled}` (GLM ≥ 4.5, DeepSeek). */
  case ThinkingType
  /** Handled natively by the Anthropic transport (adaptive / budget). */
  case AnthropicNative
  /** Handled natively by the Responses transport (`reasoning.effort`). */
  case ResponsesNative
  /** Provider takes no reasoning parameter — omit entirely. */
  case NoReasoning

/** Temperature policy: most providers get no temperature at all (upstream sends
  * none by default); some forbid it; some pin it.
  */
enum TemperaturePolicy:
  case Default
  case Omit
  case Fixed(value: Double)

/** Declarative description of one model provider, mirroring the upstream harness's
  * `ProviderProfile` (providers/base.py). Behavior lives in the wire
  * transports; profiles are data plus small quirk hooks.
  */
final case class Profile(
    name: String,
    aliases: List[String] = Nil,
    displayName: String = "",
    baseUrl: String = "",
    apiMode: ApiMode = ApiMode.ChatCompletions,
    /** API-key env var names, highest priority first. Empty = keyless or
      * externally-authenticated (OAuth/AWS).
      */
    keyEnvVars: List[String] = Nil,
    /** Env vars overriding the base URL (checked before `baseUrl`). */
    baseUrlEnvVars: List[String] = Nil,
    defaultHeaders: Map[String, String] = Map.empty,
    temperature: TemperaturePolicy = TemperaturePolicy.Default,
    defaultMaxTokens: Maybe[Int] = Absent,
    reasoningStyle: ReasoningStyle = ReasoningStyle.NoReasoning,
    supportsVision: Boolean = false,
    defaultAuxModel: String = "",
    /** Curated model ids offered when the live `/models` fetch fails. */
    staticModels: List[String] = Nil,
    /** True when this build cannot drive the provider (OAuth broker, AWS
      * SigV4, subprocess transports). Selection produces a clear error.
      */
    unsupported: Boolean = false,
    unsupportedReason: String = ""
):
  def slugAndAliases: List[String] = name :: aliases
end Profile

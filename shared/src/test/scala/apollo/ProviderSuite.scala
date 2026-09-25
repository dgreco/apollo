// SPDX-FileCopyrightText: 2026 David Greco <greco@acm.org>
//
// SPDX-License-Identifier: Apache-2.0

package apollo

import apollo.provider.*
import kyo.{Absent, Present}

class ReasoningSuite extends munit.FunSuite:

  test("clamp passes supported levels through") {
    assertEquals(Reasoning.clamp("high", Seq("low", "medium", "high")), "high")
  }

  test("clamp picks the nearest WEAKER level, never escalating") {
    assertEquals(Reasoning.clamp("xhigh", Seq("low", "medium", "high")), "high")
    assertEquals(Reasoning.clamp("medium", Seq("low", "high", "max")), "low")
  }

  test("explicit overrides win before clamping") {
    assertEquals(Reasoning.clamp("xhigh", Reasoning.glm52._1, Reasoning.glm52._2), "max")
    assertEquals(Reasoning.clamp("medium", Reasoning.kimiK3._1, Reasoning.kimiK3._2), "high")
  }

  test("'none' is never a degradation target; bespoke levels pass through") {
    assertEquals(Reasoning.clamp("low", Seq("none", "high")), "high")
    assertEquals(Reasoning.clamp("turbo", Seq("low", "high")), "turbo")
  }

  test("fromConfigValue handles the ladder and disable") {
    assertEquals(Reasoning.fromConfigValue("medium"), Present(Reasoning.Config(true, "medium")))
    assertEquals(Reasoning.fromConfigValue("none"), Present(Reasoning.Config(false, "none")))
    assertEquals(Reasoning.fromConfigValue("bogus"), Absent)
    assertEquals(Reasoning.fromConfigValue(""), Absent)
  }

class ApiModeSuite extends munit.FunSuite:

  test("host-mandated modes: exact host or dot-suffix, never substring") {
    assertEquals(ApiMode.hostMandated("https://api.anthropic.com"), Present(ApiMode.AnthropicMessages))
    assertEquals(ApiMode.hostMandated("https://api.openai.com/v1"), Present(ApiMode.CodexResponses))
    assertEquals(ApiMode.hostMandated("https://api.x.ai/v1"), Present(ApiMode.CodexResponses))
    assertEquals(
      ApiMode.hostMandated("https://bedrock-runtime.us-east-1.amazonaws.com"),
      Present(ApiMode.BedrockConverse)
    )
    // Lookalike hosts must NOT match (upstream issue #32243).
    assertEquals(ApiMode.hostMandated("https://api.anthropic.com.evil.example"), Absent)
    assertEquals(ApiMode.hostMandated("https://notapi.openai.com.example.org"), Absent)
  }

  test("path-based mandates: /anthropic suffix and kimi coding route") {
    assertEquals(
      ApiMode.hostMandated("https://api.minimax.io/anthropic"),
      Present(ApiMode.AnthropicMessages)
    )
    assertEquals(ApiMode.hostMandated("https://api.kimi.com/coding"), Present(ApiMode.AnthropicMessages))
  }

  test("config-file api_mode spellings are canonicalized") {
    assertEquals(ApiMode.parse("openai"), Present(ApiMode.ChatCompletions))
    assertEquals(ApiMode.parse("chat-completions"), Present(ApiMode.ChatCompletions))
    assertEquals(ApiMode.parse("responses"), Present(ApiMode.CodexResponses))
    assertEquals(ApiMode.parse("anthropic-messages"), Present(ApiMode.AnthropicMessages))
    assertEquals(ApiMode.parse("bedrock"), Present(ApiMode.BedrockConverse))
    assertEquals(ApiMode.parse("nonsense"), Absent)
  }

class ProfilesSuite extends munit.FunSuite:

  test("every the upstream harness provider slug from the config example resolves") {
    val slugsFromConfigExample = List(
      "openrouter", "nous", "nous-api", "anthropic", "gemini", "zai", "kimi-coding",
      "minimax", "minimax-cn", "huggingface", "nvidia", "xiaomi", "arcee", "ollama-cloud",
      "deepinfra", "kilocode", "ai-gateway", "azure-foundry", "lmstudio", "custom",
      "ollama", "vllm", "llamacpp"
    )
    slugsFromConfigExample.foreach(slug => assert(Profiles.find(slug).nonEmpty, s"missing: $slug"))
  }

  test("aliases resolve to their canonical profile") {
    assertEquals(Profiles.find("glm").map(_.name), Present("zai"))
    assertEquals(Profiles.find("grok").map(_.name), Present("xai"))
    assertEquals(Profiles.find("claude").map(_.name), Present("anthropic"))
    assertEquals(Profiles.find("ollama").map(_.name), Present("custom"))
  }

  test("unsupported providers carry an actionable reason") {
    // Still unsupported (the ChatGPT OAuth broker / external OAuth flows).
    val codex = Profiles.find("openai-codex")
    assert(codex.exists(_.unsupported))
    assert(codex.exists(_.unsupportedReason.nonEmpty))
    assert(Profiles.find("minimax-oauth").exists(_.unsupported))
    // vertex is now supported via model.base_url + model.key_cmd (gcloud token).
    assert(Profiles.find("vertex").exists(!_.unsupported))
  }

  test("bedrock is supported (SigV4 implemented) and offers models") {
    val bedrock = Profiles.find("bedrock")
    assert(bedrock.exists(!_.unsupported))
    assertEquals(bedrock.map(_.apiMode), Present(apollo.provider.ApiMode.BedrockConverse))
    assert(bedrock.exists(_.staticModels.nonEmpty))
  }

  test("auto-detect order entries all exist") {
    Profiles.autoDetectOrder.foreach(slug => assert(Profiles.find(slug).nonEmpty, s"missing: $slug"))
  }

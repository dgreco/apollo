// SPDX-FileCopyrightText: 2026 David Greco <greco@acm.org>
//
// SPDX-License-Identifier: Apache-2.0

package apollo.provider

import apollo.core.*
import apollo.util.Jx
import kyo.*

/** Anthropic prompt-cache breakpoints in `AnthropicTransport.buildBody`. */
class PromptCacheSuite extends munit.FunSuite:

  private def rt(base: String): ResolvedRuntime =
    ResolvedRuntime(
      providerSlug = "anthropic", displayName = "a", model = "claude-opus-4-6", baseUrl = base,
      apiKey = Present("k"), apiMode = ApiMode.AnthropicMessages, headers = Map.empty, profile = Absent,
      reasoning = Absent, maxTokens = Absent, contextLength = Absent, streaming = false)

  private def req(base: String, cache: Boolean): TurnRequest =
    TurnRequest(
      runtime = rt(base),
      systemPrompt = "You are a helpful agent.",
      messages = List(Message.user("hi"), Message.assistant("hello there")),
      tools = List(ToolSpec("read_file", "reads", """{"type":"object"}"""),
                   ToolSpec("write_file", "writes", """{"type":"object"}""")),
      promptCache = cache)

  test("anthropic host + promptCache adds three ephemeral breakpoints") {
    val body = Jx.render(AnthropicTransport.buildBody(req("https://api.anthropic.com", cache = true), stream = false))
    val n = "\"cache_control\"".r.findAllIn(body).length
    assertEquals(n, 3, s"expected 3 breakpoints (system, tools, last message): $body")
    assert(body.contains("ephemeral"), body)
  }

  test("disabled by flag, and skipped on non-anthropic compatible hosts") {
    val off = Jx.render(AnthropicTransport.buildBody(req("https://api.anthropic.com", cache = false), stream = false))
    assert(!off.contains("cache_control"), "flag off → no breakpoints")
    val compat = Jx.render(AnthropicTransport.buildBody(req("https://api.minimax.io/anthropic", cache = true), stream = false))
    assert(!compat.contains("cache_control"), "non-anthropic host → no breakpoints")
  }

  test("cacheLastBlock marks only the final block of the final message") {
    val blocks = List(
      Jx.obj("role" -> Jx.str("user"), "content" -> Jx.arr(Jx.obj("type" -> Jx.str("text"), "text" -> Jx.str("a")))),
      Jx.obj("role" -> Jx.str("assistant"), "content" -> Jx.arr(
        Jx.obj("type" -> Jx.str("text"), "text" -> Jx.str("b")),
        Jx.obj("type" -> Jx.str("text"), "text" -> Jx.str("c")))))
    val out = AnthropicTransport.cacheLastBlock(blocks)
    assert(!Jx.render(out.head).contains("cache_control"), "first message untouched")
    val lastRendered = Jx.render(out.last)
    assertEquals("\"cache_control\"".r.findAllIn(lastRendered).length, 1, "exactly one breakpoint on last msg")
    assert(lastRendered.indexOf("cache_control") > lastRendered.indexOf("\"c\""), lastRendered)
  }
end PromptCacheSuite

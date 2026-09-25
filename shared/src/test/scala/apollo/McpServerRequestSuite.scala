// SPDX-FileCopyrightText: 2026 David Greco <greco@acm.org>
//
// SPDX-License-Identifier: Apache-2.0

package apollo.mcp

import apollo.core.*
import apollo.util.Jx
import apollo.util.Jx.*
import kyo.*
import kyo.Structure.Value

/** Server→client MCP requests: sampling param/result conversion, elicitation
  * shapes, and McpManager's dispatch routing. */
class McpServerRequestSuite extends munit.FunSuite:

  private def run[A](v: A < (Sync & Async)): A =
    import AllowUnsafe.embrace.danger
    KyoApp.Unsafe.runAndBlock(20.seconds)(v).getOrThrow

  private def p(json: String): Value = Jx.parse(json).getOrElse(Jx.obj())

  test("sampling: parse messages, systemPrompt, maxTokens; shape result") {
    val params = p("""{"systemPrompt":"be terse","maxTokens":256,"messages":[
      {"role":"user","content":{"type":"text","text":"hi"}},
      {"role":"assistant","content":{"type":"text","text":"yo"}}]}""")
    val msgs = McpSampling.parseMessages(params)
    assertEquals(msgs.length, 2)
    assertEquals(msgs(0).role, Role.User)
    assertEquals(msgs(0).content, List(Content.Text("hi")))
    assertEquals(msgs(1).role, Role.Assistant)
    assertEquals(McpSampling.systemPrompt(params), "be terse")
    assertEquals(McpSampling.maxTokens(params), Present(256))
    val r = McpSampling.result("done", "gpt-x")
    assertEquals((r / "role").asStr, Present("assistant"))
    assertEquals((r / "content" / "text").asStr, Present("done"))
    assertEquals((r / "model").asStr, Present("gpt-x"))
    assertEquals((r / "stopReason").asStr, Present("endTurn"))
  }

  test("sampling: image content block parses to Content.Image") {
    val params = p("""{"messages":[{"role":"user","content":{"type":"image","data":"QUJD","mimeType":"image/png"}}]}""")
    assertEquals(McpSampling.parseMessages(params), List(Message(Role.User, List(Content.Image("image/png", "QUJD")), Absent)))
  }

  test("elicitation: single-string schema field detected; shapes well-formed") {
    val params = p("""{"message":"name?","requestedSchema":{"type":"object","properties":{"name":{"type":"string"}}}}""")
    assertEquals(McpElicitation.singleStringField(params), Some("name"))
    // two string fields → no single field
    val two = p("""{"requestedSchema":{"properties":{"a":{"type":"string"},"b":{"type":"string"}}}}""")
    assertEquals(McpElicitation.singleStringField(two), None)
    assertEquals((McpElicitation.accept("name", "Ada") / "action").asStr, Present("accept"))
    assertEquals((McpElicitation.accept("name", "Ada") / "content" / "name").asStr, Present("Ada"))
    assertEquals((McpElicitation.decline / "action").asStr, Present("decline"))
    assertEquals((McpElicitation.cancel / "action").asStr, Present("cancel"))
  }

  test("dispatchServerRequest routes to wired handlers and refuses the rest") {
    McpManager.setSamplingHandler(_ => Result.succeed(Jx.obj("ok" -> Jx.str("s"))))
    McpManager.setElicitationHandler(_ => Result.succeed(McpElicitation.decline))
    assertEquals(run(McpManager.dispatchServerRequest("sampling/createMessage", Jx.obj())),
      Result.succeed(Jx.obj("ok" -> Jx.str("s"))))
    assertEquals(run(McpManager.dispatchServerRequest("elicitation/create", Jx.obj())),
      Result.succeed(McpElicitation.decline))
    assert(run(McpManager.dispatchServerRequest("nonsense/method", Jx.obj())).isFailure)
  }
end McpServerRequestSuite

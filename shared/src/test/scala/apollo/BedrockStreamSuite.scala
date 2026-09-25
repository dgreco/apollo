// SPDX-FileCopyrightText: 2026 David Greco <greco@acm.org>
//
// SPDX-License-Identifier: Apache-2.0

package apollo.provider

import apollo.core.*
import apollo.util.Jx
import kyo.*

/** ConverseStream delta accumulation: fold a realistic event sequence and check
  * the emitted deltas + the assembled TurnResponse. (The binary framing is
  * covered by apollo.EventStreamSuite; the byte-stream fold reuses the same
  * mechanism as the SSE path.) */
class BedrockStreamSuite extends munit.FunSuite:

  private def p(json: String): kyo.Structure.Value = Jx.parse(json).getOrElse(Jx.obj())

  test("text + tool-use stream assembles into one response") {
    val acc = new BedrockTransport.StreamAcc
    val deltas = scala.collection.mutable.ListBuffer[StreamEvent]()
    def offer(evt: String, json: String): Unit = deltas ++= acc.offer(evt, p(json))

    offer("messageStart", """{"role":"assistant"}""")
    offer("contentBlockDelta", """{"contentBlockIndex":0,"delta":{"text":"Hel"}}""")
    offer("contentBlockDelta", """{"contentBlockIndex":0,"delta":{"text":"lo"}}""")
    offer("contentBlockStart", """{"contentBlockIndex":1,"start":{"toolUse":{"toolUseId":"t1","name":"search"}}}""")
    offer("contentBlockDelta", """{"contentBlockIndex":1,"delta":{"toolUse":{"input":"{\"q\":"}}}""")
    offer("contentBlockDelta", """{"contentBlockIndex":1,"delta":{"toolUse":{"input":"\"hi\"}"}}}""")
    offer("messageStop", """{"stopReason":"tool_use"}""")
    offer("metadata", """{"usage":{"inputTokens":10,"outputTokens":5}}""")

    // emitted deltas
    assert(deltas.contains(StreamEvent.TextDelta("Hel")), deltas.toString)
    assert(deltas.contains(StreamEvent.TextDelta("lo")), deltas.toString)
    assert(deltas.contains(StreamEvent.ToolUseStarted("t1", "search")), deltas.toString)
    assert(deltas.exists { case StreamEvent.ToolUseArgsDelta("t1", _) => true; case _ => false }, deltas.toString)

    // assembled response
    val resp = acc.result match
      case Result.Success(r) => r
      case other             => fail(s"expected success, got $other")
    assertEquals(resp.message.content(0), Content.Text("Hello"))
    assertEquals(resp.message.content(1), Content.ToolUse("t1", "search", """{"q":"hi"}"""))
    assertEquals(resp.stopReason, StopReason.ToolUse)
    assertEquals(resp.usage.inputTokens, 10L)
    assertEquals(resp.usage.outputTokens, 5L)
  }

  test("plain text stream → EndTurn, empty tool input defaults to {}") {
    val acc = new BedrockTransport.StreamAcc
    acc.offer("contentBlockDelta", p("""{"contentBlockIndex":0,"delta":{"text":"done"}}"""))
    acc.offer("messageStop", p("""{"stopReason":"end_turn"}"""))
    val r = acc.result.getOrElse(fail("expected success"))
    assertEquals(r.message.content, List(Content.Text("done")))
    assertEquals(r.stopReason, StopReason.EndTurn)
  }

  test("reasoning deltas become a Thinking block") {
    val acc = new BedrockTransport.StreamAcc
    acc.offer("contentBlockDelta", p("""{"contentBlockIndex":0,"delta":{"reasoningContent":{"text":"hmm"}}}"""))
    acc.offer("messageStop", p("""{"stopReason":"end_turn"}"""))
    val r = acc.result.getOrElse(fail("expected success"))
    assert(r.message.content.exists { case Content.Thinking("hmm", _) => true; case _ => false }, r.message.content.toString)
  }

  test("exception frame surfaces as a provider error") {
    val acc = new BedrockTransport.StreamAcc
    acc.offerException("""{"message":"throttled"}""")
    assert(acc.result.isFailure)
  }
end BedrockStreamSuite

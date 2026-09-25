// SPDX-FileCopyrightText: 2026 David Greco <greco@acm.org>
//
// SPDX-License-Identifier: Apache-2.0

package apollo.agent

import apollo.core.*

/** Pure guard predicates (repetition + empty-response). */
class GuardsSuite extends munit.FunSuite:

  private def toolMsg(name: String, args: String): Message =
    Message(Role.Assistant, List(Content.ToolUse("id1", name, args)))

  test("signature: tool turns sign by name+args, text turns by text, empty is None") {
    assertEquals(Guards.signature(toolMsg("read_file", """{"path":"a"}""")),
      Some("""tools read_file{"path":"a"}"""))
    assertEquals(Guards.signature(Message.assistant("hello")), Some("text hello"))
    assertEquals(Guards.signature(Message(Role.Assistant, Nil)), None)
    assertEquals(Guards.signature(Message.assistant("   ")), None)
  }

  test("isRepeating fires only when the last `limit` signatures are identical") {
    val a = Guards.signature(toolMsg("t", "x")).toList
    // Build a history of 4 identical signatures via pushSignature.
    val h4 = (1 to 4).foldLeft(List.empty[String])((h, _) =>
      Guards.pushSignature(h, Guards.signature(toolMsg("t", "x")), 4))
    assert(Guards.isRepeating(h4, 4), "four identical → repeating")
    val h3 = (1 to 3).foldLeft(List.empty[String])((h, _) =>
      Guards.pushSignature(h, Guards.signature(toolMsg("t", "x")), 4))
    assert(!Guards.isRepeating(h3, 4), "three identical, limit 4 → not yet")
    // A differing final signature breaks the run.
    val mixed = Guards.pushSignature(h3, Guards.signature(toolMsg("t", "y")), 4)
    assert(!Guards.isRepeating(mixed, 4), "different last → not repeating")
  }

  test("pushSignature caps at `limit`, drops None, and disables at 0") {
    val h = (1 to 10).foldLeft(List.empty[String])((acc, i) =>
      Guards.pushSignature(acc, Some(s"s$i"), 4))
    assertEquals(h.length, 4)
    assertEquals(h, List("s7", "s8", "s9", "s10"))
    // None (empty turn) leaves history unchanged.
    assertEquals(Guards.pushSignature(h, None, 4), h)
    // limit 0 disables tracking entirely.
    assertEquals(Guards.pushSignature(h, Some("z"), 0), Nil)
    assert(!Guards.isRepeating(List("a", "a", "a"), 1), "limit 1 never fires")
  }

  test("isEmptyResponse: no text and no tool calls") {
    assert(Guards.isEmptyResponse(Message(Role.Assistant, Nil)))
    assert(Guards.isEmptyResponse(Message.assistant("   \n ")))
    assert(!Guards.isEmptyResponse(Message.assistant("done")))
    assert(!Guards.isEmptyResponse(toolMsg("t", "x")))
  }
end GuardsSuite

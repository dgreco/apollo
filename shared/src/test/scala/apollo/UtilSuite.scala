// SPDX-FileCopyrightText: 2026 David Greco <greco@acm.org>
//
// SPDX-License-Identifier: Apache-2.0

package apollo

import apollo.config.EnvFile
import apollo.util.{Jx, Sse, Utf8}
import apollo.util.Jx.*
import kyo.{Absent, Chunk, Present, Result}

class EnvFileSuite extends munit.FunSuite:

  test("parses KEY=VALUE with comments, export, and quotes") {
    val parsed = EnvFile.parse(
      """# comment
        |OPENROUTER_API_KEY=sk-or-123
        |export ANTHROPIC_API_KEY="sk-ant-456"
        |QUOTED='single $literal'
        |ESCAPED="line1\nline2"
        |INLINE=value # trailing comment
        |BROKEN LINE
        |=nokey
        |""".stripMargin
    )
    assertEquals(parsed("OPENROUTER_API_KEY"), "sk-or-123")
    assertEquals(parsed("ANTHROPIC_API_KEY"), "sk-ant-456")
    assertEquals(parsed("QUOTED"), "single $literal")
    assertEquals(parsed("ESCAPED"), "line1\nline2")
    assertEquals(parsed("INLINE"), "value")
    assert(!parsed.contains("BROKEN"))
  }

class SseSuite extends munit.FunSuite:

  test("parses events split across chunks") {
    val (e1, s1) = Sse.feed(Sse.State.empty, "data: {\"a\":")
    assertEquals(e1, Nil)
    val (e2, s2) = Sse.feed(s1, "1}\n\ndata: [DONE]\n\n")
    assertEquals(e2.map(_.data), List("{\"a\":1}", "[DONE]"))
    assertEquals(Sse.flush(s2), Nil)
  }

  test("joins multi-line data and reads event names") {
    val (events, _) = Sse.feed(Sse.State.empty, "event: message_start\ndata: line1\ndata: line2\n\n")
    assertEquals(events.head.name, Present("message_start"))
    assertEquals(events.head.data, "line1\nline2")
  }

  test("ignores comments and CR line endings") {
    val (events, _) = Sse.feed(Sse.State.empty, ": keep-alive\r\ndata: x\r\n\r\n")
    assertEquals(events.map(_.data), List("x"))
  }

  test("flush surfaces an unterminated trailing event") {
    val (_, s) = Sse.feed(Sse.State.empty, "data: tail\n")
    assertEquals(Sse.flush(s).map(_.data), List("tail"))
  }

class Utf8Suite extends munit.FunSuite:

  test("carries split multi-byte characters to the next chunk") {
    val bytes = "héllo ☤".getBytes("UTF-8")
    // Split inside the two-byte é and inside the three-byte ☤.
    for split <- 1 until bytes.length do
      val (s1, rest1) = Utf8.decodePrefix(bytes.take(split))
      val (s2, rest2) = Utf8.decodePrefix(rest1 ++ bytes.drop(split))
      assertEquals(s1 + s2 + new String(rest2, "UTF-8"), "héllo ☤", s"split at $split")
  }

class JxSuite extends munit.FunSuite:

  test("round-trips construction, rendering and parsing") {
    val v = Jx.obj(
      "s" -> Jx.str("x"), "n" -> Jx.num(42), "d" -> Jx.num(1.5),
      "b" -> Jx.bool(true), "z" -> Jx.nul,
      "arr" -> Jx.arr(Jx.str("a"), Jx.num(1))
    )
    val parsed = Jx.parse(Jx.render(v))
    assert(parsed.isSuccess)
    val p = parsed.getOrElse(Jx.nul)
    assertEquals((p / "s").asStr, Present("x"))
    assertEquals((p / "n").asLong, Present(42L))
    assertEquals((p / "d").asDouble, Present(1.5))
    assertEquals((p / "b").asBool, Present(true))
    assertEquals((p / "arr").asArr.map(_.length), Present(2))
    assertEquals((p / "missing").asStr, Absent)
  }

  test("objOf omits absent fields; withField replaces") {
    val v = Jx.objOf("keep" -> Present(Jx.num(1)), "drop" -> Absent)
    assertEquals((v / "drop").asStr, Absent)
    assertEquals((v.withField("keep", Jx.num(2)) / "keep").asLong, Present(2L))
  }

  test("deepMerge merges nested objects and replaces scalars") {
    val base = Jx.obj("a" -> Jx.obj("x" -> Jx.num(1), "y" -> Jx.num(2)), "b" -> Jx.num(3))
    val over = Jx.obj("a" -> Jx.obj("y" -> Jx.num(9)))
    val m    = base.deepMerge(over)
    assertEquals((m / "a" / "x").asLong, Present(1L))
    assertEquals((m / "a" / "y").asLong, Present(9L))
    assertEquals((m / "b").asLong, Present(3L))
  }

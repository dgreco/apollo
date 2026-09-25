// SPDX-FileCopyrightText: 2026 David Greco <greco@acm.org>
//
// SPDX-License-Identifier: Apache-2.0

package apollo

import apollo.util.EventStream

class EventStreamSuite extends munit.FunSuite:

  private def frame(evt: String, json: String): Array[Byte] =
    EventStream.encode(List(":event-type" -> evt, ":content-type" -> "application/json"), json.getBytes("UTF-8"))

  test("encode/feed round-trips a single frame") {
    val bytes = frame("contentBlockDelta", """{"delta":{"text":"hi"}}""")
    val (frames, st) = EventStream.feed(EventStream.empty, bytes)
    assertEquals(frames.length, 1)
    assertEquals(frames.head.eventType, Some("contentBlockDelta"))
    assertEquals(new String(frames.head.payload, "UTF-8"), """{"delta":{"text":"hi"}}""")
    assertEquals(st.buf.length, 0)
  }

  test("multiple frames in one feed") {
    val bytes = frame("messageStart", """{"role":"assistant"}""") ++
                frame("contentBlockDelta", """{"delta":{"text":"a"}}""") ++
                frame("messageStop", """{"stopReason":"end_turn"}""")
    val (frames, _) = EventStream.feed(EventStream.empty, bytes)
    assertEquals(frames.map(_.eventType.getOrElse("")), List("messageStart", "contentBlockDelta", "messageStop"))
  }

  test("partial frame across chunk boundaries is buffered") {
    val bytes = frame("contentBlockDelta", """{"delta":{"text":"hello world"}}""")
    val cut   = bytes.length / 2
    val (f1, s1) = EventStream.feed(EventStream.empty, bytes.take(cut))
    assertEquals(f1, Nil)                         // not enough yet
    val (f2, s2) = EventStream.feed(s1, bytes.drop(cut))
    assertEquals(f2.length, 1)
    assertEquals(f2.head.eventType, Some("contentBlockDelta"))
    assertEquals(s2.buf.length, 0)
  }

  test("byte-at-a-time feeding still reassembles") {
    val bytes = frame("contentBlockDelta", """{"delta":{"text":"x"}}""")
    var st = EventStream.empty
    val collected = scala.collection.mutable.ListBuffer[EventStream.Frame]()
    bytes.foreach { b =>
      val (fs, ns) = EventStream.feed(st, Array(b))
      collected ++= fs; st = ns
    }
    assertEquals(collected.length, 1)
    assertEquals(collected.head.eventType, Some("contentBlockDelta"))
  }

  test("string headers captured; exception message-type surfaced") {
    val f = EventStream.encode(List(":message-type" -> "exception", ":exception-type" -> "ThrottlingException"),
      """{"message":"slow down"}""".getBytes("UTF-8"))
    val (frames, _) = EventStream.feed(EventStream.empty, f)
    assertEquals(frames.head.messageType, Some("exception"))
    assertEquals(frames.head.headers.get(":exception-type"), Some("ThrottlingException"))
  }
end EventStreamSuite

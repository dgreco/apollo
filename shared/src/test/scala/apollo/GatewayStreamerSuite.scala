package apollo.gateway

import kyo.*

class GatewayStreamerSuite extends munit.FunSuite:

  private def run[A](v: A < (Sync & Async)): A =
    import AllowUnsafe.embrace.danger
    KyoApp.Unsafe.runAndBlock(20.seconds)(v).getOrThrow

  private class Recorder:
    val sends = new java.util.concurrent.ConcurrentLinkedQueue[String]()
    val edits = new java.util.concurrent.ConcurrentLinkedQueue[String]()
    val chunked = new java.util.concurrent.ConcurrentLinkedQueue[String]()
    def sentList: List[String] =
      import scala.jdk.CollectionConverters.*; sends.asScala.toList
    def editList: List[String] =
      import scala.jdk.CollectionConverters.*; edits.asScala.toList
    def chunkedList: List[String] =
      import scala.jdk.CollectionConverters.*; chunked.asScala.toList

  private def streamer(
      r: Recorder, clock: () => Long,
      interval: Long = 100, threshold: Int = 5, max: Int = 2000,
      chunk: String => List[String] = s => s.grouped(2000).toList
  ) = new GatewayStreamer(
    editIntervalMs = interval, bufferThreshold = threshold, maxMessage = max, chunk = chunk,
    send = t => Sync.defer { r.sends.add(t); Present("m1") },
    edit = (_, t) => Sync.defer { r.edits.add(t); () },
    chunkedSend = t => Sync.defer { r.chunked.add(t); () },
    now = clock)

  test("first content delta posts the initial message; whitespace waits") {
    val r = new Recorder
    var t = 0L
    val s = streamer(r, () => t)
    run(s.onDelta("   ")) // whitespace only → no post yet
    assertEquals(r.sentList, Nil)
    run(s.onDelta("hi"))  // real content → post
    assertEquals(r.sentList, List("   hi"))
  }

  test("edits are throttled by interval AND char threshold") {
    val r = new Recorder
    var t = 0L
    val s = streamer(r, () => t, interval = 100, threshold = 5)
    run(s.onDelta("hello"))            // post at t=0
    t = 50;  run(s.onDelta("!"))       // interval not elapsed → no edit
    t = 150; run(s.onDelta(" world"))  // elapsed + grew ≥5 → edit
    t = 160; run(s.onDelta("x"))       // interval not elapsed → no edit
    assertEquals(r.sentList, List("hello"))
    assertEquals(r.editList, List("hello! world"))
  }

  test("finalize edits the streamed message to the final text (single chunk)") {
    val r = new Recorder
    var t = 0L
    val s = streamer(r, () => t)
    run(s.onDelta("partial"))
    run(s.finalize("the complete final answer"))
    assertEquals(r.sentList, List("partial"))
    assertEquals(r.editList, List("the complete final answer"))
    assertEquals(r.chunkedList, Nil)
  }

  test("finalize overflow: edit first chunk, send the rest") {
    val r = new Recorder
    var t = 0L
    val s = streamer(r, () => t, max = 10, chunk = _.grouped(10).toList)
    run(s.onDelta("start"))
    run(s.finalize("0123456789ABCDEFGHIJxyz"))
    assertEquals(r.editList, List("0123456789"))
    assertEquals(r.chunkedList, List("ABCDEFGHIJ", "xyz"))
  }

  test("no deltas (non-streaming model) → finalize sends the whole thing chunked") {
    val r = new Recorder
    var t = 0L
    val s = streamer(r, () => t)
    run(s.finalize("full response, never streamed"))
    assertEquals(r.sentList, Nil)
    assertEquals(r.editList, Nil)
    assertEquals(r.chunkedList, List("full response, never streamed"))
  }
end GatewayStreamerSuite

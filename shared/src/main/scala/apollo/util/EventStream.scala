package apollo.util

/** Decoder for AWS's `application/vnd.amazon.eventstream` binary framing (used by
  * Bedrock ConverseStream). Each message:
  *
  * {{{
  *   [4B total length][4B headers length][4B prelude CRC]
  *   [headers…][payload…][4B message CRC]
  * }}}
  *
  * Headers are name/typed-value pairs; only string-typed headers (the ones
  * Bedrock uses: `:event-type`, `:content-type`, `:message-type`,
  * `:exception-type`) are captured. CRCs are not verified (the HTTPS transport
  * already guarantees integrity). `feed` is incremental and buffers partial
  * frames across chunk boundaries, mirroring `apollo.util.Sse`.
  */
object EventStream:

  final case class Frame(headers: Map[String, String], payload: Array[Byte]):
    def eventType: Option[String] = headers.get(":event-type")
    def messageType: Option[String] = headers.get(":message-type")

  final case class State(buf: Array[Byte])
  val empty: State = State(Array.emptyByteArray)

  private def u32(b: Array[Byte], off: Int): Int =
    ((b(off) & 0xff) << 24) | ((b(off + 1) & 0xff) << 16) | ((b(off + 2) & 0xff) << 8) | (b(off + 3) & 0xff)
  private def u16(b: Array[Byte], off: Int): Int =
    ((b(off) & 0xff) << 8) | (b(off + 1) & 0xff)

  /** Feed more bytes; return any complete frames plus the carry-over state. */
  def feed(state: State, incoming: Array[Byte]): (List[Frame], State) =
    var buf   = if state.buf.isEmpty then incoming else state.buf ++ incoming
    val out   = scala.collection.mutable.ListBuffer[Frame]()
    var going = true
    while going do
      if buf.length < 12 then going = false
      else
        val total = u32(buf, 0)
        if total < 16 || buf.length < total then going = false
        else
          val headersLen   = u32(buf, 4)
          val headers      = parseHeaders(buf, 12, math.min(headersLen, total - 16))
          val payloadStart = 12 + headersLen
          val payloadLen   = total - headersLen - 16
          val payload =
            if payloadLen > 0 && payloadStart + payloadLen <= buf.length then
              buf.slice(payloadStart, payloadStart + payloadLen)
            else Array.emptyByteArray
          out += Frame(headers, payload)
          buf = buf.drop(total)
    (out.toList, State(buf))

  private def parseHeaders(b: Array[Byte], start: Int, len: Int): Map[String, String] =
    val end = start + len
    var off = start
    val m   = scala.collection.mutable.Map[String, String]()
    while off < end - 1 do
      val nameLen = b(off) & 0xff; off += 1
      if off + nameLen > end then off = end
      else
        val name = new String(b, off, nameLen, "UTF-8"); off += nameLen
        if off >= end then off = end
        else
          val typ = b(off) & 0xff; off += 1
          typ match
            case 0 | 1 => ()                              // bool true/false — no value bytes
            case 2     => off += 1                        // byte
            case 3     => off += 2                        // short
            case 4     => off += 4                        // integer
            case 5     => off += 8                        // long
            case 6     => if off + 2 <= end then off += 2 + u16(b, off) else off = end // byte array
            case 7 =>                                     // string
              if off + 2 <= end then
                val l = u16(b, off); off += 2
                if off + l <= end then { m(name) = new String(b, off, l, "UTF-8"); off += l } else off = end
              else off = end
            case 8 => off += 8                            // timestamp
            case 9 => off += 16                           // uuid
            case _ => off = end                           // unknown → stop
    m.toMap

  /** Encode one frame (string headers + payload) — for tests and mock servers. */
  def encode(headers: List[(String, String)], payload: Array[Byte]): Array[Byte] =
    val hb = new java.io.ByteArrayOutputStream()
    headers.foreach { (name, value) =>
      val nb = name.getBytes("UTF-8"); val vb = value.getBytes("UTF-8")
      hb.write(nb.length & 0xff)
      hb.write(nb)
      hb.write(7)                       // string type
      hb.write((vb.length >> 8) & 0xff); hb.write(vb.length & 0xff)
      hb.write(vb)
    }
    val headerBytes = hb.toByteArray
    val total = 12 + headerBytes.length + payload.length + 4
    val out = new java.io.ByteArrayOutputStream()
    def w32(n: Int): Unit =
      out.write((n >> 24) & 0xff); out.write((n >> 16) & 0xff); out.write((n >> 8) & 0xff); out.write(n & 0xff)
    w32(total); w32(headerBytes.length); w32(0)  // prelude CRC unused
    out.write(headerBytes); out.write(payload); w32(0) // message CRC unused
    out.toByteArray
end EventStream

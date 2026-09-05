package apollo.gateway

import kyo.*

/** Drives gateway "watch it type" streaming for one turn: it accumulates the
  * model's text deltas, posts an initial chat message, and edits it in place
  * as more text arrives — throttled by `edit_interval` (min seconds between
  * edits) and `buffer_threshold` (min new chars since the last edit) so
  * platform rate limits aren't tripped. `finalize` delivers the complete
  * text: it edits the streamed message to the first chunk and sends any
  * overflow as follow-on messages (chunked at the platform limit).
  *
  * Degrades cleanly: if the model didn't stream (no deltas — e.g.
  * `streaming: false`), no message is posted mid-turn and `finalize` just
  * sends the full text via the chunked sender.
  */
final class GatewayStreamer(
    editIntervalMs: Long,
    bufferThreshold: Int,
    maxMessage: Int,
    chunk: String => List[String],
    send: String => (Maybe[String] < (Sync & Async)),
    edit: (String, String) => (Unit < (Sync & Async)),
    chunkedSend: String => (Unit < (Sync & Async)),
    now: () => Long = () => java.lang.System.currentTimeMillis()
):
  private val buffer          = new StringBuilder
  @volatile private var msgId: Maybe[String] = Absent
  @volatile private var lastEditAt   = 0L
  @volatile private var lastEditedLen = 0

  /** Feed one text delta. Posts the first message once there's something to
    * show, then edits at most as often as the throttle allows.
    */
  def onDelta(text: String): Unit < (Sync & Async) =
    if text.isEmpty then ()
    else
      buffer.synchronized(buffer.append(text))
      val current = buffer.synchronized(buffer.toString)
      msgId match
        case Absent =>
          // First visible content: post the initial message.
          if current.trim.nonEmpty then
            send(clamp(current)).map { id =>
              msgId = id
              lastEditAt = now()
              lastEditedLen = current.length
            }
          else ()
        case Present(id) =>
          val due = now() - lastEditAt >= editIntervalMs
          val grew = current.length - lastEditedLen >= bufferThreshold
          if due && grew then
            lastEditAt = now()
            lastEditedLen = current.length
            edit(id, clamp(current))
          else ()

  /** Deliver the complete response. Edits the streamed message to the first
    * chunk and sends the rest; or, if nothing was streamed, sends it whole.
    */
  def finalize(fullText: String): Unit < (Sync & Async) =
    msgId match
      case Absent => chunkedSend(fullText)
      case Present(id) =>
        chunk(fullText) match
          case Nil          => ()
          case head :: Nil  => edit(id, head)
          case head :: rest => edit(id, head).andThen(Kyo.foreachDiscard(rest)(chunkedSend))

  private def clamp(s: String): String =
    if s.length <= maxMessage then s else s.take(maxMessage)
end GatewayStreamer

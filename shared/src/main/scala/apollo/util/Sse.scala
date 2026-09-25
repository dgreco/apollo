// SPDX-FileCopyrightText: 2026 David Greco <greco@acm.org>
//
// SPDX-License-Identifier: Apache-2.0

package apollo.util

import kyo.*

/** Incremental Server-Sent-Events parser.
  *
  * Providers stream responses as `text/event-stream` over a POST request;
  * kyo-http's typed SSE decoding is GET-shaped and decodes error bodies as
  * events, so we parse the raw byte stream by hand: feed chunks in, get
  * completed events out. State is carried explicitly so the caller can fold
  * over a byte stream.
  */
object Sse:

  /** One SSE event: the (possibly multi-line, joined) data payload plus the
    * optional `event:` name.
    */
  final case class Event(data: String, name: Maybe[String] = Absent)

  /** Parser state: undecoded UTF-8 remainder + partially accumulated event. */
  final case class State(
      buffer: String = "",
      dataLines: List[String] = Nil,
      eventName: Maybe[String] = Absent
  )

  object State:
    val empty: State = State()

  /** Feeds a decoded text chunk, returning completed events + new state. */
  def feed(state: State, chunk: String): (List[Event], State) =
    val text  = state.buffer + chunk
    val lines = splitKeepRemainder(text)
    lines.complete.foldLeft((List.empty[Event], state.copy(buffer = lines.remainder))) {
      case ((events, st), line) => processLine(events, st, line)
    } match
      case (events, st) => (events.reverse, st)

  /** Flushes a trailing event that wasn't terminated by a blank line. */
  def flush(state: State): List[Event] =
    if state.dataLines.nonEmpty then List(Event(state.dataLines.reverse.mkString("\n"), state.eventName))
    else Nil

  private def processLine(events: List[Event], st: State, rawLine: String): (List[Event], State) =
    val line = if rawLine.endsWith("\r") then rawLine.dropRight(1) else rawLine
    if line.isEmpty then
      // Blank line terminates the current event.
      if st.dataLines.nonEmpty then
        (Event(st.dataLines.reverse.mkString("\n"), st.eventName) :: events,
         st.copy(dataLines = Nil, eventName = Absent))
      else (events, st.copy(eventName = Absent))
    else if line.startsWith(":") then (events, st) // comment / keep-alive
    else
      fieldOf(line) match
        case ("data", value)  => (events, st.copy(dataLines = value :: st.dataLines))
        case ("event", value) => (events, st.copy(eventName = Present(value)))
        case _                => (events, st) // id / retry / unknown fields ignored
  end processLine

  private def fieldOf(line: String): (String, String) =
    line.indexOf(':') match
      case -1 => (line, "")
      case i =>
        val value = line.drop(i + 1)
        (line.take(i), if value.startsWith(" ") then value.drop(1) else value)

  private final case class Lines(complete: List[String], remainder: String)

  private def splitKeepRemainder(text: String): Lines =
    val parts = text.split("\n", -1)
    Lines(parts.init.toList, parts.last)
end Sse

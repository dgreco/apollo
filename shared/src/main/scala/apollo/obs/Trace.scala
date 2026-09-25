// SPDX-FileCopyrightText: 2026 David Greco <greco@acm.org>
//
// SPDX-License-Identifier: Apache-2.0

package apollo.obs

/** A per-turn trace: a tree of spans collected in memory for OTLP export and
  * the `/trace` view. The root is `agent.turn`; children are `llm.call` (one per
  * provider round), `tool.<name>`, `compress`, and `checkpoint`. Content-free —
  * only names, enums, counts, and durations, never prompts/args/results.
  *
  * Not thread-safe: a turn drives it from a single fiber sequence (the loop and
  * its sequential tool round). Timestamps are epoch-nanos (ms resolution),
  * matching the OTLP span fields. */
object TraceContext:
  /** A span opened with `begin`, awaiting `end`. */
  final case class Open(id: String, name: String, startNanos: Long, parent: Option[String])

  private def uuid32(): String = java.util.UUID.randomUUID.toString.replace("-", "")
  def newTraceId(): String     = uuid32()
  def newSpanId(): String      = uuid32().take(16)

  /** Indented tree, ordered by start time, for the `/trace` command. */
  def renderTree(traceId: String, spans: List[apollo.obs.Otlp.Span]): String =
    if spans.isEmpty then s"trace ${traceId.take(8)} — (no spans)"
    else
      val byParent = spans.groupBy(_.parent)
      val roots    = spans.filter(_.parent.isEmpty).sortBy(_.startNanos)
      val sb = new StringBuilder
      sb.append(s"trace ${traceId.take(8)}")
      def ms(s: apollo.obs.Otlp.Span): Long = (s.endNanos - s.startNanos) / 1_000_000L
      def attrStr(s: apollo.obs.Otlp.Span): String =
        if s.attrs.isEmpty then ""
        else
          val parts = s.attrs.map {
            case apollo.obs.Otlp.Attr.S(k, v) => s"$k=$v"
            case apollo.obs.Otlp.Attr.I(k, v) => s"$k=$v"
            case apollo.obs.Otlp.Attr.B(k, v) => s"$k=$v"
          }
          "  [" + parts.mkString(", ") + "]"
      def walk(s: apollo.obs.Otlp.Span, depth: Int): Unit =
        val indent = "  " * depth
        val mark   = if s.error then " ✗" else ""
        sb.append(s"\n$indent${s.name}  ${ms(s)}ms$mark${attrStr(s)}")
        byParent.getOrElse(Some(s.spanId), Nil).sortBy(_.startNanos).foreach(c => walk(c, depth + 1))
      roots.foreach(r => walk(r, 1))
      sb.toString

final class TraceContext(val serviceName: String):
  val traceId: String = TraceContext.newTraceId()
  private val buf = scala.collection.mutable.ListBuffer[apollo.obs.Otlp.Span]()
  private var current: Option[String] = None

  private def nowNanos: Long = java.lang.System.currentTimeMillis() * 1_000_000L

  /** Open a child span under the current parent, making it the new current. */
  def begin(name: String): TraceContext.Open =
    val open = TraceContext.Open(TraceContext.newSpanId(), name, nowNanos, current)
    current = Some(open.id)
    open

  /** Close a span: record it (with attrs) and restore its parent as current. */
  def end(open: TraceContext.Open, error: Boolean = false,
          attrs: List[apollo.obs.Otlp.Attr] = Nil): apollo.obs.Otlp.Span =
    val span = apollo.obs.Otlp.Span(open.name, open.id, open.parent, open.startNanos, nowNanos, attrs, error)
    buf += span
    current = open.parent
    span

  def spans: List[apollo.obs.Otlp.Span] = buf.toList
  def isEmpty: Boolean                  = buf.isEmpty

  /** The current span id, for stamping log records with span context. */
  def currentSpanId: Option[String] = current

  /** Indented tree for `/trace`. */
  def render: String = TraceContext.renderTree(traceId, buf.toList)
end TraceContext

// SPDX-FileCopyrightText: 2026 David Greco <greco@acm.org>
//
// SPDX-License-Identifier: Apache-2.0

package apollo.obs

import apollo.util.Jx
import kyo.*
import kyo.Structure.Value

/** Pure OTLP/HTTP JSON payload building for traces. Content-free by
  * construction — callers pass only names, enums, counts, and durations, never
  * prompts / tool args / results. Emitted to an OTLP receiver's `/v1/traces`. */
object Otlp:

  enum Attr:
    case S(key: String, value: String)
    case I(key: String, value: Long)
    case B(key: String, value: Boolean)

  final case class Span(
      name: String,
      spanId: String,
      parent: Option[String],
      startNanos: Long,
      endNanos: Long,
      attrs: List[Attr],
      error: Boolean)

  private def attrJson(a: Attr): Value = a match
    case Attr.S(k, v) => Jx.obj("key" -> Jx.str(k), "value" -> Jx.obj("stringValue" -> Jx.str(v)))
    case Attr.I(k, v) => Jx.obj("key" -> Jx.str(k), "value" -> Jx.obj("intValue" -> Jx.str(v.toString)))
    case Attr.B(k, v) => Jx.obj("key" -> Jx.str(k), "value" -> Jx.obj("boolValue" -> Jx.bool(v)))

  private def spanJson(traceId: String, s: Span): Value =
    Jx.objOf(
      "traceId"           -> Present(Jx.str(traceId)),
      "spanId"            -> Present(Jx.str(s.spanId)),
      "parentSpanId"      -> Maybe.fromOption(s.parent).map(Jx.str),
      "name"              -> Present(Jx.str(s.name)),
      "kind"              -> Present(Jx.num(1L)), // INTERNAL
      "startTimeUnixNano" -> Present(Jx.str(s.startNanos.toString)),
      "endTimeUnixNano"   -> Present(Jx.str(s.endNanos.toString)),
      "attributes"        -> Present(Jx.arr(s.attrs.map(attrJson))),
      "status"            -> Present(Jx.obj("code" -> Jx.num(if s.error then 2L else 1L))))

  /** A full OTLP `/v1/traces` request body for one trace's spans. */
  def traceJson(serviceName: String, traceId: String, spans: List[Span]): String =
    Jx.render(Jx.obj(
      "resourceSpans" -> Jx.arr(Jx.obj(
        "resource" -> resource(serviceName),
        "scopeSpans" -> Jx.arr(Jx.obj(
          "scope" -> scope,
          "spans" -> Jx.arr(spans.map(s => spanJson(traceId, s)))))))))

  // --- metrics ------------------------------------------------------------

  /** A monotonic cumulative counter reading. */
  final case class CounterView(name: String, value: Long, unit: String = "1")

  /** A histogram reading (count/sum/min/max, ms by default). Exported as a
    * single-bucket OTLP histogram — enough for latency dashboards. */
  final case class HistoView(name: String, count: Long, sum: Double, min: Double, max: Double, unit: String = "ms")

  private def sumJson(startNanos: Long, nowNanos: Long, c: CounterView): Value =
    Jx.obj(
      "name" -> Jx.str(c.name), "unit" -> Jx.str(c.unit),
      "sum" -> Jx.obj(
        "aggregationTemporality" -> Jx.num(2L), // CUMULATIVE
        "isMonotonic"            -> Jx.bool(true),
        "dataPoints" -> Jx.arr(Jx.obj(
          "asInt"             -> Jx.str(c.value.toString),
          "startTimeUnixNano" -> Jx.str(startNanos.toString),
          "timeUnixNano"      -> Jx.str(nowNanos.toString)))))

  private def histoJson(startNanos: Long, nowNanos: Long, h: HistoView): Value =
    Jx.obj(
      "name" -> Jx.str(h.name), "unit" -> Jx.str(h.unit),
      "histogram" -> Jx.obj(
        "aggregationTemporality" -> Jx.num(2L),
        "dataPoints" -> Jx.arr(Jx.obj(
          "startTimeUnixNano" -> Jx.str(startNanos.toString),
          "timeUnixNano"      -> Jx.str(nowNanos.toString),
          "count"             -> Jx.str(h.count.toString),
          "sum"               -> Jx.num(h.sum),
          "min"               -> Jx.num(h.min),
          "max"               -> Jx.num(h.max),
          "bucketCounts"      -> Jx.arr(Jx.str(h.count.toString)),
          "explicitBounds"    -> Jx.arr(List.empty[Value])))))

  /** A full OTLP `/v1/metrics` request body. */
  def metricsJson(serviceName: String, startNanos: Long, nowNanos: Long,
                  counters: List[CounterView], histos: List[HistoView]): String =
    Jx.render(Jx.obj(
      "resourceMetrics" -> Jx.arr(Jx.obj(
        "resource" -> resource(serviceName),
        "scopeMetrics" -> Jx.arr(Jx.obj(
          "scope" -> scope,
          "metrics" -> Jx.arr(
            counters.map(c => sumJson(startNanos, nowNanos, c)) ++
              histos.map(h => histoJson(startNanos, nowNanos, h)))))))))

  // --- logs ---------------------------------------------------------------

  /** One structured log record. `severityNumber` follows OTLP (TRACE=1, DEBUG=5,
    * INFO=9, WARN=13, ERROR=17). */
  final case class LogRecord(nanos: Long, severityText: String, severityNumber: Int,
                             body: String, attrs: List[Attr] = Nil,
                             traceId: Option[String] = None, spanId: Option[String] = None)

  private def logRecordJson(r: LogRecord): Value =
    Jx.objOf(
      "timeUnixNano"         -> Present(Jx.str(r.nanos.toString)),
      "observedTimeUnixNano" -> Present(Jx.str(r.nanos.toString)),
      "severityNumber"       -> Present(Jx.num(r.severityNumber.toLong)),
      "severityText"         -> Present(Jx.str(r.severityText)),
      "body"                 -> Present(Jx.obj("stringValue" -> Jx.str(r.body))),
      "attributes"           -> Present(Jx.arr(r.attrs.map(attrJson))),
      "traceId"              -> Maybe.fromOption(r.traceId).map(Jx.str),
      "spanId"               -> Maybe.fromOption(r.spanId).map(Jx.str))

  /** A full OTLP `/v1/logs` request body. */
  def logsJson(serviceName: String, records: List[LogRecord]): String =
    Jx.render(Jx.obj(
      "resourceLogs" -> Jx.arr(Jx.obj(
        "resource" -> resource(serviceName),
        "scopeLogs" -> Jx.arr(Jx.obj(
          "scope" -> scope,
          "logRecords" -> Jx.arr(records.map(logRecordJson))))))))

  private def resource(serviceName: String): Value =
    Jx.obj("attributes" -> Jx.arr(
      Jx.obj("key" -> Jx.str("service.name"), "value" -> Jx.obj("stringValue" -> Jx.str(serviceName)))))

  private def scope: Value = Jx.obj("name" -> Jx.str("apollo"))
end Otlp

package apollo.obs

import apollo.util.Jx
import apollo.util.Jx.*
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
        "resource" -> Jx.obj("attributes" -> Jx.arr(
          Jx.obj("key" -> Jx.str("service.name"), "value" -> Jx.obj("stringValue" -> Jx.str(serviceName))))),
        "scopeSpans" -> Jx.arr(Jx.obj(
          "scope" -> Jx.obj("name" -> Jx.str("apollo")),
          "spans" -> Jx.arr(spans.map(s => spanJson(traceId, s)))))))))
end Otlp

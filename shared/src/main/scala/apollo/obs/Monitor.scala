package apollo.obs

import apollo.config.ApolloConfig
import apollo.provider.{ProviderError, Transport}
import kyo.*

/** Content-free observability export (mirrors Hermes's monitoring plane):
  * one OTLP trace per agent turn — a parent `agent.turn` span plus a child span
  * per tool call — POSTed to an OTLP/HTTP receiver. Only names, enums, counts,
  * and durations are exported; never prompts, tool args, or results. Opt-in
  * (`monitoring.export.otlp.enabled` + endpoint) and fail-open. */
object Monitor:

  final case class ToolTiming(name: String, startMs: Long, endMs: Long, error: Boolean)

  def enabled(config: ApolloConfig): Boolean = config.otlpEnabled && config.otlpEndpoint.nonEmpty

  private def uuid32(): String = java.util.UUID.randomUUID.toString.replace("-", "")
  private def uuid16(): String = uuid32().take(16)

  def exportTurn(
      config: ApolloConfig,
      exitReason: String,
      apiCalls: Long,
      inTokens: Long,
      outTokens: Long,
      startMs: Long,
      endMs: Long,
      tools: List[ToolTiming]
  ): Unit < (Sync & Async) =
    if !enabled(config) then Sync.defer(())
    else
      Sync.defer {
        val ms2n    = (m: Long) => m * 1_000_000L
        val traceId = uuid32()
        val turnId  = uuid16()
        val toolSpans = tools.map { t =>
          Otlp.Span(s"tool.${t.name}", uuid16(), Some(turnId), ms2n(t.startMs), ms2n(t.endMs),
            List(Otlp.Attr.S("tool.name", t.name), Otlp.Attr.B("error", t.error)), t.error)
        }
        val turnSpan = Otlp.Span("agent.turn", turnId, None, ms2n(startMs), ms2n(endMs),
          List(
            Otlp.Attr.S("exit_reason", exitReason),
            Otlp.Attr.I("api_calls", apiCalls),
            Otlp.Attr.I("tokens.input", inTokens),
            Otlp.Attr.I("tokens.output", outTokens),
            Otlp.Attr.I("tool_calls", tools.length.toLong)),
          exitReason.startsWith("error"))
        Otlp.traceJson(config.otlpServiceName, traceId, turnSpan :: toolSpans)
      }.map { body =>
        val url     = s"${config.otlpEndpoint}/v1/traces"
        val headers = ("content-type" -> "application/json") :: config.otlpHeaders.toList
        Abort.run[ProviderError](Transport.postJson(url, headers, body, 10.seconds)).unit
      }
end Monitor

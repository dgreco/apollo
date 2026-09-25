// SPDX-FileCopyrightText: 2026 David Greco <greco@acm.org>
//
// SPDX-License-Identifier: Apache-2.0

package apollo.obs

import apollo.config.ApolloConfig
import apollo.http.{HttpError, Transport}
import kyo.*

/** Content-free observability export (mirrors Hermes's monitoring plane):
  * one OTLP trace per agent turn — a parent `agent.turn` span plus a child span
  * per tool call — POSTed to an OTLP/HTTP receiver. Only names, enums, counts,
  * and durations are exported; never prompts, tool args, or results. Opt-in
  * (`monitoring.export.otlp.enabled` + endpoint) and fail-open. */
object Monitor:

  final case class ToolTiming(name: String, startMs: Long, endMs: Long, error: Boolean)

  /** Traces pillar (`monitoring.export.otlp.enabled`). Kept as `enabled` for
    * back-compat. */
  def enabled(config: ApolloConfig): Boolean       = config.otlpEnabled && config.otlpEndpoint.nonEmpty
  def tracesEnabled(config: ApolloConfig): Boolean = enabled(config)
  def metricsEnabled(config: ApolloConfig): Boolean = config.otlpMetricsEnabled && config.otlpEndpoint.nonEmpty
  def logsEnabled(config: ApolloConfig): Boolean    = config.otlpLogsEnabled && config.otlpEndpoint.nonEmpty

  private def uuid32(): String = java.util.UUID.randomUUID.toString.replace("-", "")
  private def uuid16(): String = uuid32().take(16)

  /** POST one OTLP body to `<endpoint><path>`, fail-open (errors swallowed). */
  private def post(config: ApolloConfig, path: String, body: String): Unit < (Sync & Async) =
    val url     = s"${config.otlpEndpoint}$path"
    val headers = ("content-type" -> "application/json") :: config.otlpHeaders.toList
    Abort.run[HttpError](Transport.postJson(url, headers, body, 10.seconds)).unit

  /** Export everything collected for a turn — traces (the span tree), the
    * cumulative metric snapshot, and any buffered log records — POSTing each
    * enabled OTLP pillar. Fail-open and content-free. Call fire-and-forget. */
  def exportAll(config: ApolloConfig, tc: TraceContext): Unit < (Sync & Async) =
    val traces =
      if tracesEnabled(config) && !tc.isEmpty then
        post(config, "/v1/traces", Otlp.traceJson(config.otlpServiceName, tc.traceId, tc.spans))
      else Sync.defer(())
    val metrics =
      if metricsEnabled(config) then
        Sync.defer(Metrics.snapshot).map { case (counters, histos) =>
          val now = java.lang.System.currentTimeMillis() * 1_000_000L
          post(config, "/v1/metrics",
            Otlp.metricsJson(config.otlpServiceName, Metrics.processStartNanos, now, counters, histos))
        }
      else Sync.defer(())
    val logs =
      if logsEnabled(config) then
        Sync.defer(ObsLog.drain()).map { records =>
          if records.isEmpty then Sync.defer(())
          else post(config, "/v1/logs", Otlp.logsJson(config.otlpServiceName, records))
        }
      else Sync.defer(())
    traces.andThen(metrics).andThen(logs)

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
        Abort.run[HttpError](Transport.postJson(url, headers, body, 10.seconds)).unit
      }
end Monitor

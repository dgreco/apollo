// SPDX-FileCopyrightText: 2026 David Greco <greco@acm.org>
//
// SPDX-License-Identifier: Apache-2.0

package apollo.obs

import kyo.*
import kyo.stats.internal.{StatsRegistry, Summary}
import java.util.concurrent.atomic.AtomicLong

/** In-process metrics for the agent loop: cumulative counters and latency
  * histograms. Always updated (cheap, lock-free) — export to OTLP is a separate,
  * opt-in read of these values. Reads back for the `/metrics` view and the OTLP
  * `/v1/metrics` body.
  *
  * Counters are held as our own cumulative `AtomicLong`s: the Kyo registry's
  * `Counter.get()` is a *consuming delta* read (built for periodic scrapers),
  * which can't serve repeated cumulative reads. Latency uses the Kyo registry's
  * histograms, whose `Summary` snapshot is cumulative and re-readable.
  *
  * Content-free: only names, counts, and durations — never prompts/results. */
object Metrics:
  import AllowUnsafe.embrace.danger

  /** Epoch-nanos the process began collecting — the OTLP `startTimeUnixNano`
    * for cumulative points. */
  val processStartNanos: Long = java.lang.System.currentTimeMillis() * 1_000_000L

  private val root = StatsRegistry.scope("apollo")

  // Latency buckets tuned for LLM/tool round-trips (ms).
  private val latencyBounds: Array[Double] =
    Array[Double](1, 5, 10, 25, 50, 100, 250, 500, 1000, 2500, 5000, 10000, 30000, 60000)

  private val nTurns  = new AtomicLong(0)
  private val nApi    = new AtomicLong(0)
  private val nTools  = new AtomicLong(0)
  private val nErrors = new AtomicLong(0)
  private val nInTok  = new AtomicLong(0)
  private val nOutTok = new AtomicLong(0)

  private val hTurn = root.histogram("turn_ms", "turn wall time (ms)", latencyBounds)
  private val hLlm  = root.histogram("llm_ms", "provider call time (ms)", latencyBounds)
  private val hTool = root.histogram("tool_ms", "tool execution time (ms)", latencyBounds)
  private val hTtft = root.histogram("ttft_ms", "time to first streamed token (ms)", latencyBounds)

  def turnCompleted(reason: String, apiCalls: Long, inTok: Long, outTok: Long, turnMs: Long): Unit < Sync =
    Sync.defer {
      nTurns.incrementAndGet()
      nApi.addAndGet(apiCalls)
      nInTok.addAndGet(inTok)
      nOutTok.addAndGet(outTok)
      if reason.startsWith("error") then nErrors.incrementAndGet()
      hTurn.observe(turnMs)
      ()
    }

  def llmCall(ms: Long): Unit < Sync = Sync.defer { hLlm.observe(ms); () }
  def ttft(ms: Long): Unit < Sync    = Sync.defer { hTtft.observe(ms); () }
  def toolCall(ms: Long, error: Boolean): Unit < Sync =
    Sync.defer {
      nTools.incrementAndGet()
      if error then nErrors.incrementAndGet()
      hTool.observe(ms)
      ()
    }

  /** Current cumulative values, as OTLP export views. */
  def snapshot: (List[Otlp.CounterView], List[Otlp.HistoView]) =
    val counters = List(
      Otlp.CounterView("apollo.turns", nTurns.get()),
      Otlp.CounterView("apollo.api_calls", nApi.get()),
      Otlp.CounterView("apollo.tool_calls", nTools.get()),
      Otlp.CounterView("apollo.errors", nErrors.get()),
      Otlp.CounterView("apollo.tokens_input", nInTok.get(), "token"),
      Otlp.CounterView("apollo.tokens_output", nOutTok.get(), "token"))
    val histos = List(
      view("apollo.turn_ms", hTurn.summary()),
      view("apollo.llm_ms", hLlm.summary()),
      view("apollo.tool_ms", hTool.summary()),
      view("apollo.ttft_ms", hTtft.summary()))
    (counters, histos)

  private def view(name: String, s: Summary): Otlp.HistoView =
    Otlp.HistoView(name, s.count, s.sum, if s.count == 0 then 0.0 else s.min, s.max)

  /** A compact human-readable table for the `/metrics` REPL command. Native-safe
    * (manual padding — no java.util.Formatter). */
  def render: String =
    val (counters, _) = snapshot
    def pad(s: String, n: Int): String = if s.length >= n then s else s + (" " * (n - s.length))
    def r(d: Double): String = math.round(d).toString
    def line(label: String, s: Summary): String =
      if s.count == 0 then s"  ${pad(label, 10)} —"
      else s"  ${pad(label, 10)} n=${pad(s.count.toString, 5)} avg=${r(s.sum / s.count)}ms" +
        s" min=${r(s.min)} max=${r(s.max)}"
    val head = counters.map(c => s"  ${pad(c.name.stripPrefix("apollo."), 14)} ${c.value}").mkString("\n")
    val hist = List(
      line("turn_ms", hTurn.summary()),
      line("llm_ms", hLlm.summary()),
      line("tool_ms", hTool.summary()),
      line("ttft_ms", hTtft.summary())).mkString("\n")
    s"counters:\n$head\nlatency (ms):\n$hist"
end Metrics

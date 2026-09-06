package apollo.cli

/** The per-turn status line shown after each response — model, context-window
  * usage, token counts, and turn time — modeled on the Hermes status bar.
  *
  * Pure and Native-safe: numbers are humanized without java.util.Formatter
  * (which misbehaves on Scala Native).
  */
object StatusBar:

  /** Compact number: 1234 → "1.2k", 2_000_000 → "2.0M", 500 → "500". */
  def human(n: Long): String =
    if n >= 1000000L then s"${n / 1000000L}.${(n % 1000000L) / 100000L}M"
    else if n >= 1000L then s"${n / 1000L}.${(n % 1000L) / 100L}k"
    else n.toString

  /** Milliseconds → "3.2s". */
  def secs(ms: Long): String =
    val s = ms / 1000L
    s"$s.${(ms % 1000L) / 100L}s"

  /** A short bar like "▓▓▓░░░░░" for a 0..1 fraction over `width` cells. */
  def gauge(fraction: Double, width: Int): String =
    val clamped = math.max(0.0, math.min(1.0, fraction))
    val filled  = (clamped * width).round.toInt
    ("▓" * filled) + ("░" * (width - filled))

  /** Output tokens per second over the turn (0 when no turn has run). */
  def rate(outTok: Long, turnMs: Long): Long =
    if turnMs > 0 then outTok * 1000L / turnMs else 0L

  /** The status line body (caller applies dim styling) — model · context gauge ·
    * tokens · token-rate · turn time. Modeled on the Hermes bottom bar. */
  def render(model: String, ctxTokens: Long, ctxWindow: Int,
             inTok: Long, outTok: Long, turnMs: Long): String =
    val ctx =
      if ctxWindow > 0 then
        val pct = (ctxTokens * 100L / ctxWindow.toLong)
        s"ctx $pct% ${gauge(ctxTokens.toDouble / ctxWindow, 8)} ${human(ctxTokens)}/${human(ctxWindow.toLong)}"
      else s"ctx ${human(ctxTokens)}"
    val r = rate(outTok, turnMs)
    val rateStr = if r > 0 then s" · ${r} t/s" else ""
    s"$model · $ctx · ${human(inTok)} in / ${human(outTok)} out$rateStr · ${secs(turnMs)}"
end StatusBar

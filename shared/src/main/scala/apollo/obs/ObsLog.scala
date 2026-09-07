package apollo.obs

import kyo.*

/** apollo's structured logging facade over `kyo.Log`. Every call emits through
  * kyo's Log effect (visible when an apollo console logger is installed at the
  * chosen level) and, when OTLP-logs export is on, also buffers an OTLP
  * `LogRecord` stamped with the active trace/span id — so the same instrumented
  * lines become a readable console trace, kyo log events, and exported OTLP logs.
  *
  * Content-free by construction: callers pass operation names, enums, counts,
  * and durations — never prompts, tool arguments, or results. */
object ObsLog:

  private val queue = new java.util.concurrent.ConcurrentLinkedQueue[Otlp.LogRecord]()
  private val CapMax = 2000
  @volatile private var capture = false

  /** Turn OTLP-logs buffering on/off (set from config at startup). */
  def setCapture(on: Boolean): Unit = capture = on

  /** Parse a config level string to a `kyo.Log.Level` (default silent). */
  def parseLevel(name: String): Log.Level = name.trim.toLowerCase match
    case "trace"           => Log.Level.trace
    case "debug"           => Log.Level.debug
    case "info"            => Log.Level.info
    case "warn" | "warning" => Log.Level.warn
    case "error"           => Log.Level.error
    case _                 => Log.Level.silent

  private def severityNumber(sev: String): Int = sev match
    case "TRACE" => 1
    case "DEBUG" => 5
    case "INFO"  => 9
    case "WARN"  => 13
    case "ERROR" => 17
    case _       => 0

  private def enqueue(sev: String, msg: String, tc: Maybe[TraceContext]): Unit =
    if capture then
      if queue.size >= CapMax then queue.poll() // bounded: drop oldest
      queue.add(Otlp.LogRecord(
        java.lang.System.currentTimeMillis() * 1_000_000L, sev, severityNumber(sev), msg,
        traceId = tc.map(_.traceId).toList.headOption,
        spanId  = tc.toList.flatMap(_.currentSpanId).headOption))
      ()

  def trace(msg: String, tc: Maybe[TraceContext] = Absent): Unit < Sync =
    Sync.defer(enqueue("TRACE", msg, tc)).andThen(Log.trace(msg))
  def debug(msg: String, tc: Maybe[TraceContext] = Absent): Unit < Sync =
    Sync.defer(enqueue("DEBUG", msg, tc)).andThen(Log.debug(msg))
  def info(msg: String, tc: Maybe[TraceContext] = Absent): Unit < Sync =
    Sync.defer(enqueue("INFO", msg, tc)).andThen(Log.info(msg))
  def warn(msg: String, tc: Maybe[TraceContext] = Absent): Unit < Sync =
    Sync.defer(enqueue("WARN", msg, tc)).andThen(Log.warn(msg))
  def error(msg: String, tc: Maybe[TraceContext] = Absent): Unit < Sync =
    Sync.defer(enqueue("ERROR", msg, tc)).andThen(Log.error(msg))

  /** Drain buffered OTLP log records (per-turn flush). */
  def drain(): List[Otlp.LogRecord] =
    val out = List.newBuilder[Otlp.LogRecord]
    var r = queue.poll()
    while r != null do
      out += r
      r = queue.poll()
    out.result()

  /** Run `body` with an apollo console logger installed at `lvl`; `silent` keeps
    * the ambient (quiet) logger, preserving the default clean chat UI. */
  def withLogger[A, S](lvl: Log.Level)(body: A < S)(using Frame): A < S =
    if lvl == Log.Level.silent then body
    else
      import AllowUnsafe.embrace.danger
      Log.let(Log(Log.Unsafe.ConsoleLogger("apollo", lvl)))(body)
end ObsLog

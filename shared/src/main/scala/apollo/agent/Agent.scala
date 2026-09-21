package apollo.agent

import apollo.core.*
import apollo.http.HttpError
import apollo.obs.{Metrics, Monitor, ObsLog, Otlp, TraceContext}
import apollo.provider.*
import apollo.session.SessionStore
import apollo.tools.{ToolContext, ToolRegistry}
import kyo.*

/** UI-facing callbacks for one turn. All are fire-and-forget; a callback
  * must never break the loop.
  */
final case class TurnCallbacks(
    onTextDelta: String => Unit < (Sync & Async) = _ => (),
    onThinkingDelta: String => Unit < (Sync & Async) = _ => (),
    onToolStart: (String, String) => Unit < (Sync & Async) = (_, _) => (),
    onToolComplete: (String, String, Boolean) => Unit < (Sync & Async) = (_, _, _) => (),
    onStatus: String => Unit < (Sync & Async) = _ => ()
)

final case class TurnResult(
    finalResponse: String,
    exitReason: String, // text_response | max_iterations | interrupted | empty_response | repetition_guard | error(...)
    apiCalls: Int,
    usage: Usage,
    interrupted: Boolean
)

enum AgentError:
  case Provider(error: HttpError)
  case Interrupted

/** The conversation engine: user message → model call → tool rounds → final
  * text, with the upstream harness's structural invariants (strict role alternation,
  * persist-before-execute, tool-call/result pairing) and its retry,
  * iteration-budget-with-grace-call, and compression behaviors.
  */
final class Agent(
    runtime: ResolvedRuntime,
    toolCtx: ToolContext,
    store: SessionStore,
    sessionId: String,
    maxIterations: Maybe[Int],
    interruptFlag: java.util.concurrent.atomic.AtomicBoolean
):
  private val config    = toolCtx.config
  private var messages  = List.empty[Message]
  private var lastPromptTokens = 0L
  private var totalUsage       = Usage.zero
  private var apiCalls         = 0
  private var nudgeState       = Nudges.State()
  private var nudgeHydrated     = false

  // --- per-turn loop guards (reset in runTurn) ----------------------------
  private var recentSignatures = List.empty[String]
  private var emptyRetriesUsed = 0
  private var checkpointedThisTurn = false
  private var turnStartMs = 0L

  // --- observability: per-turn trace tree + live-call timing ---------------
  private var turnTrace: TraceContext          = null.asInstanceOf[TraceContext]
  private var rootOpen: TraceContext.Open      = null.asInstanceOf[TraceContext.Open]
  private var lastTraceCtx: Maybe[TraceContext] = Absent
  @volatile private var llmCallStartMs = 0L
  @volatile private var llmFirstTokenMs = 0L
  /** The most recent completed turn's trace tree — for `/trace` and the console
    * trace view. */
  def lastTrace: Maybe[TraceContext] = lastTraceCtx

  private def traceBegin(name: String): TraceContext.Open =
    if turnTrace != null then turnTrace.begin(name) else null.asInstanceOf[TraceContext.Open]
  private def traceEnd(open: TraceContext.Open, error: Boolean, attrs: List[Otlp.Attr]): Unit =
    if turnTrace != null && open != null then { turnTrace.end(open, error, attrs); () }
  private def traceMaybe: Maybe[TraceContext] = if turnTrace != null then Present(turnTrace) else Absent

  private var sid           = sessionId
  private var forceCompress = false

  def history: List[Message]           = messages
  def restore(h: List[Message]): Unit  = messages = h

  // --- REPL slash-command hooks -------------------------------------------
  /** The session this agent is currently persisting to (changes on /resume). */
  def currentSession: String = sid
  /** Swap the active session (transcript + history) — used by `/resume`. */
  def resumeSession(id: String, h: List[Message]): Unit =
    sid = id
    messages = h
    nudgeHydrated = false // re-derive the nudge cadence from the restored history
  /** Force a compaction before the next model call, bypassing the threshold. */
  def requestCompress(): Unit    = forceCompress = true
  def usageSnapshot: Usage       = totalUsage
  def apiCallCount: Int          = apiCalls
  def lastPromptTokenCount: Long = lastPromptTokens

  /** Thread-safe steer buffer (`/steer`): messages pushed here are injected as
    * user turns after the next tool round, without interrupting the run. */
  private val steerBox = new java.util.concurrent.ConcurrentLinkedQueue[String]()
  def steer(message: String): Unit = { steerBox.add(message); () }

  private def nudgeSettings(toolNames: List[String]): Nudges.Settings =
    Nudges.Settings(
      memoryInterval = config.memoryNudgeInterval,
      skillInterval = config.skillCreationNudgeInterval,
      memoryEnabled = config.memoryEnabled,
      hasMemoryTool = toolNames.contains("memory"),
      hasSkillTool = toolNames.contains("skill_manage")
    )

  def runTurn(
      userMessage: Message,
      systemPrompt: String,
      toolNames: List[String],
      callbacks: TurnCallbacks
  ): TurnResult < (Sync & Async) =
    interruptFlag.set(false)
    recentSignatures = Nil
    emptyRetriesUsed = 0
    checkpointedThisTurn = false
    turnStartMs = java.lang.System.currentTimeMillis()
    // Fresh per-turn trace: root `agent.turn` span, children added as we go.
    turnTrace = new TraceContext(config.otlpServiceName)
    rootOpen  = turnTrace.begin("agent.turn")
    val settings = nudgeSettings(toolNames)
    // Lazily hydrate the nudge cadence from restored history on the first turn
    // (Hermes parity), now that the toolset is known.
    if !nudgeHydrated then
      nudgeState = Nudges.hydrate(messages.count(_.role == Role.User), settings)
      nudgeHydrated = true
    messages = messages :+ userMessage
    // Self-improvement nudge: append an ephemeral reminder to THIS turn's
    // system prompt (never persisted, so the transcript stays clean).
    val (nextNudge, reminder) = Nudges.tick(nudgeState, settings)
    nudgeState = nextNudge
    val effectiveSystemPrompt = reminder match
      case Present(text) => s"$systemPrompt\n\n$text"
      case Absent        => systemPrompt
    ObsLog.info(
      s"turn start · model=${runtime.model} provider=${runtime.providerSlug} tools=${toolNames.length} session=$sid",
      traceMaybe)
      .andThen(store.appendMessage(sid, userMessage))
      .andThen(loop(effectiveSystemPrompt, toolNames, callbacks, iterationsThisTurn = 0, graceUsed = false))

  private def loop(
      systemPrompt: String,
      toolNames: List[String],
      callbacks: TurnCallbacks,
      iterationsThisTurn: Int,
      graceUsed: Boolean
  ): TurnResult < (Sync & Async) =
    if interruptFlag.get then
      finishTurn("", "interrupted", interrupted = true)
    else
      val budgetExhausted = maxIterations.exists(iterationsThisTurn >= _)
      if budgetExhausted && graceUsed then finishTurn("", "max_iterations", interrupted = false)
      else
        val grace = budgetExhausted // one summary call past the budget
        val prepared =
          if grace then
            messages = messages :+ Message.user(
              "You have reached the iteration limit for this turn. Summarize what you accomplished, " +
                "what remains, and any important findings. Do not call more tools."
            )
          else ()
        ObsLog.trace(s"iteration $iterationsThisTurn · messages=${messages.length}", traceMaybe)
          .andThen(maybeCompress(callbacks)).andThen {
          messages = Alternation.repair(messages)
          val request = TurnRequest(
            runtime = runtime,
            systemPrompt = systemPrompt,
            messages = messages,
            tools = if grace then Nil else ToolRegistry.definitions(toolNames, toolCtx),
            promptCache = config.promptCacheEnabled
          )
          // One `llm.call` span per provider round (prompt → response); TTFT is
          // captured from the first streamed token (see callWithRetries).
          val llmOpen = traceBegin("llm.call")
          llmCallStartMs  = java.lang.System.currentTimeMillis()
          llmFirstTokenMs = 0L
          ObsLog.debug(
            s"→ llm.call · messages=${messages.length} tools=${if grace then 0 else toolNames.length} iter=$iterationsThisTurn",
            traceMaybe
          ).andThen(callWithFallback(request, callbacks)).map { result =>
            closeLlmSpan(llmOpen, result).andThen {
              result match {
            case Result.Failure(AgentError.Interrupted) =>
              finishTurn("", "interrupted", interrupted = true)
            case Result.Failure(AgentError.Provider(err)) =>
              finishTurn("", s"error(${err.getMessage.take(300)})", interrupted = false)
            case Result.Panic(e) =>
              finishTurn("", s"error(${String.valueOf(e.getMessage).take(300)})", interrupted = false)
            case Result.Success(response) =>
              apiCalls += 1
              totalUsage = totalUsage + response.usage
              lastPromptTokens = response.usage.inputTokens + response.usage.cacheReadTokens
              val toolUses = response.message.content.collect { case tu: Content.ToolUse => tu }
              // Durability invariant: the assistant message (tool calls
              // included) is persisted BEFORE any tool executes.
              messages = messages :+ response.message
              recentSignatures =
                Guards.pushSignature(recentSignatures, Guards.signature(response.message), config.repetitionLimit)
              store.appendMessage(sid, response.message).andThen {
                if toolUses.isEmpty then
                  val text = response.message.content.collect { case Content.Text(t) => t }.mkString("\n")
                  // Empty-response guard: no text and no tool calls. Re-prompt a
                  // bounded number of times before giving up, so a transient
                  // blank reply doesn't silently end the turn.
                  if Guards.isEmptyResponse(response.message) then
                    if emptyRetriesUsed < config.emptyResponseRetries then
                      emptyRetriesUsed += 1
                      val nudge = Message.user(Guards.emptyResponseNudge)
                      messages = messages :+ nudge
                      callbacks.onStatus("empty response; re-prompting")
                        .andThen(store.appendMessage(sid, nudge))
                        .andThen(loop(systemPrompt, toolNames, callbacks, iterationsThisTurn + 1, graceUsed || grace))
                    else finishTurn(text, "empty_response", interrupted = false)
                  else finishTurn(text, "text_response", interrupted = false)
                else if Guards.isRepeating(recentSignatures, config.repetitionLimit) then
                  // Repetition guard: the model has called the same tool with the
                  // same arguments `repetition_limit` times running. Stop the loop,
                  // but stub the pending tool calls so tool_call/result pairing
                  // (and thus role alternation) stays intact in the transcript.
                  val stubbed = Message.toolResults(toolUses.map(tu =>
                    Content.ToolResult(tu.id, "[stopped: repetition guard — same tool call repeated]", true)))
                  messages = messages :+ stubbed
                  callbacks.onStatus("repetition guard: same action repeated; stopping")
                    .andThen(store.appendMessage(sid, stubbed))
                    .andThen(finishTurn(
                      s"(stopped: repeated the same action ${config.repetitionLimit} times without progress)",
                      "repetition_guard", interrupted = false))
                else
                  runToolRound(toolUses, callbacks).map { results =>
                    val resultMsg = Message.toolResults(results)
                    messages = messages :+ resultMsg
                    store.appendMessage(sid, resultMsg).andThen(drainSteer(callbacks)).andThen {
                      loop(systemPrompt, toolNames, callbacks, iterationsThisTurn + 1, graceUsed || grace)
                    }
                  }
              }
              } // result match
            }   // closeLlmSpan.andThen
          }     // callWithFallback.map
        }
  end loop

  private def runToolRound(
      toolUses: List[Content.ToolUse],
      callbacks: TurnCallbacks
  ): List[Content.ToolResult] < (Sync & Async) =
    ObsLog.trace(s"tool round · ${toolUses.length} ${if toolUses.length == 1 then "call" else "calls"}", traceMaybe)
      .andThen(maybeCheckpoint(toolUses, callbacks))
      .andThen(executeToolRound(toolUses, callbacks))

  /** Auto-checkpoint the working tree before the first file-mutating tool call
    * of a turn (Hermes-style, gated by `checkpoints.enabled`). Fail-open. */
  private def maybeCheckpoint(
      toolUses: List[Content.ToolUse], callbacks: TurnCallbacks
  ): Unit < (Sync & Async) =
    if !config.checkpointsEnabled || checkpointedThisTurn ||
      !toolUses.exists(tu => apollo.tools.Checkpoint.mutatingTools.contains(tu.name)) then Sync.defer(())
    else
      checkpointedThisTurn = true
      Sync.defer(java.time.Instant.now().toEpochMilli.toString).map { id =>
        apollo.tools.Checkpoint.create(toolCtx.cwd, id).map {
          case Result.Success(_) => callbacks.onStatus(s"checkpoint $id")
          case _                 => Sync.defer(()) // not a git repo / no commits — silent
        }
      }

  private def executeToolRound(
      toolUses: List[Content.ToolUse],
      callbacks: TurnCallbacks
  ): List[Content.ToolResult] < (Sync & Async) =
    Kyo.foreach(toolUses) { tu =>
      if interruptFlag.get then
        // Keep tool_call/result pairing intact on interrupt.
        Content.ToolResult(tu.id, s"[Tool execution cancelled — ${tu.name} was skipped due to user interrupt]", true)
      else
        Sync.defer((java.lang.System.currentTimeMillis(), traceBegin(s"tool.${tu.name}"))).map { (t0, open) =>
          ObsLog.debug(s"→ tool.${tu.name}", traceMaybe).andThen {
            callbacks.onToolStart(tu.name, tu.arguments.take(200)).andThen {
              ToolRegistry.dispatch(tu.name, tu.arguments, toolCtx).map { (output, isError) =>
                val ms = java.lang.System.currentTimeMillis() - t0
                Sync.defer(traceEnd(open, isError,
                    List(Otlp.Attr.S("tool.name", tu.name), Otlp.Attr.I("duration_ms", ms), Otlp.Attr.B("error", isError))))
                  .andThen(Metrics.toolCall(ms, isError))
                  .andThen(ObsLog.debug(s"← tool.${tu.name} · ${ms}ms${if isError then " error" else ""}", traceMaybe))
                  .andThen(callbacks.onToolComplete(tu.name, output.take(300), isError))
                  .andThen(Content.ToolResult(tu.id, output, isError))
              }
            }
          }
        }
    }.map(_.toList)

  /** Tries the primary provider, then each configured `fallback_providers`
    * entry in turn, when a provider fails after its own retries — an outage
    * or rate limit on one provider fails the turn over to the next rather
    * than aborting. An interrupt or a successful call stops the chain.
    */
  private def callWithFallback(
      request: TurnRequest,
      callbacks: TurnCallbacks
  ): Result[AgentError, TurnResponse] < (Sync & Async) =
    resolveFallbacks.map { fallbacks =>
      def attemptChain(chain: List[ResolvedRuntime]): Result[AgentError, TurnResponse] < (Sync & Async) =
        chain match
          case Nil => Result.fail(AgentError.Provider(HttpError.Network("all providers failed")))
          case rt :: rest =>
            callWithRetries(request, callbacks, attempt = 0, rt).map {
              case Result.Failure(AgentError.Provider(err)) if rest.nonEmpty && !interruptFlag.get =>
                ObsLog.warn(
                  s"provider ${rt.providerSlug} failed; falling over to ${rest.head.providerSlug} · ${err.getMessage.take(80)}",
                  traceMaybe)
                  .andThen(callbacks.onStatus(
                    s"provider ${rt.providerSlug} failed (${err.getMessage.take(80)}); " +
                      s"falling over to ${rest.head.providerSlug}"))
                  .andThen(attemptChain(rest))
              case other => other // success, interrupt, or last provider's failure
            }
      attemptChain(runtime :: fallbacks)
    }

  /** Resolves `fallback_providers` slugs to runtimes once per session,
    * dropping unresolvable ones (unknown provider / missing credentials) and
    * any that duplicate the primary.
    */
  private var fallbacksResolved = false
  private var fallbackRuntimes  = List.empty[ResolvedRuntime]
  private def resolveFallbacks: List[ResolvedRuntime] < (Sync & Async) =
    if fallbacksResolved then fallbackRuntimes
    else
      Kyo.foreach(config.fallbackProviders) { slug =>
        Abort.run[ResolveError](Runtime.resolve(config, RuntimeOverrides(provider = Present(slug)))).map {
          case Result.Success(rt) => Present(rt)
          case _                  => Absent
        }
      }.map { results =>
        fallbackRuntimes = results.toList.flatMap(_.toList)
          .filter(_.providerSlug != runtime.providerSlug)
        fallbacksResolved = true
        fallbackRuntimes
      }

  /** Retries retryable provider errors with jittered exponential backoff
    * (base 5s, cap 120s), up to `agent.api_max_retries`; an interrupt aborts
    * the wait between attempts. `rt` is the provider runtime for this attempt
    * (the primary or a fallback).
    */
  private def callWithRetries(
      request: TurnRequest,
      callbacks: TurnCallbacks,
      attempt: Int,
      rt: ResolvedRuntime
  ): Result[AgentError, TurnResponse] < (Sync & Async) =
    // The first streamed token flips the TTFT clock and, at trace level, emits
    // the finest latency signal we have — the instant the model starts
    // responding, well before the `← llm.call` round summary (debug).
    def onFirstToken(kind: String): Unit < Sync =
      Sync.defer {
        if llmFirstTokenMs == 0L then
          llmFirstTokenMs = java.lang.System.currentTimeMillis(); true
        else false
      }.map(first =>
        if first then ObsLog.trace(s"llm.call first $kind token · ttft=${llmFirstTokenMs - llmCallStartMs}ms", traceMaybe)
        else Sync.defer(()))
    val events: StreamEvent => Unit < (Sync & Async) =
      case StreamEvent.TextDelta(t)         => onFirstToken("text").andThen(callbacks.onTextDelta(t))
      case StreamEvent.ThinkingDelta(t)     => onFirstToken("thinking").andThen(callbacks.onThinkingDelta(t))
      case StreamEvent.ToolUseStarted(_, n) => ObsLog.trace(s"stream · tool-use started: $n", traceMaybe).andThen(callbacks.onStatus(s"tool: $n"))
      case _                                => ()

    val transport = WireTransport.forMode(rt.apiMode) match
      case Result.Success(t) => t
      case Result.Failure(e) => return Result.fail(AgentError.Provider(e))
      case _                 => return Result.fail(AgentError.Provider(HttpError.Protocol("no transport")))

    interruptible(transport.streamTurn(request.copy(runtime = rt))(events)).map {
      case Result.Success(resp) => Result.succeed(resp)
      case Result.Failure(AgentError.Provider(err)) if err.retryable && attempt + 1 < config.apiMaxRetries =>
        val delay = backoffSeconds(attempt)
        ObsLog.warn(s"llm retry ${attempt + 2}/${config.apiMaxRetries} in ${delay}s · ${err.getMessage.take(80)}", traceMaybe)
          .andThen(callbacks.onStatus(s"provider error (${err.getMessage.take(120)}); retry ${attempt + 2}/${config.apiMaxRetries} in ${delay}s"))
          .andThen(Async.sleep(delay.seconds))
          .andThen(callWithRetries(request, callbacks, attempt + 1, rt))
      case other => other
    }
  end callWithRetries

  /** Races the provider call against an interrupt watcher so Ctrl-C cancels
    * a stuck request. `raceFirst` (not `race`) so a failing call surfaces
    * instead of hanging.
    */
  private def interruptible(
      call: TurnResponse < (Sync & Async & Abort[HttpError])
  ): Result[AgentError, TurnResponse] < (Sync & Async) =
    def watcher: Result[AgentError, TurnResponse] < (Sync & Async) =
      Async.sleep(200.millis).andThen {
        if interruptFlag.get then Result.fail(AgentError.Interrupted)
        else watcher
      }
    val main: Result[AgentError, TurnResponse] < (Sync & Async) =
      Abort.run[HttpError](call).map {
        case Result.Success(r) => Result.succeed(r)
        case Result.Failure(e) => Result.fail(AgentError.Provider(e))
        case Result.Panic(e)   => Result.fail(AgentError.Provider(HttpError.Network(String.valueOf(e.getMessage))))
      }
    Async.raceFirst(main, watcher)

  private def backoffSeconds(attempt: Int): Int =
    val base = math.min(5.0 * math.pow(2, attempt), 120.0)
    (base * (0.75 + scala.util.Random.nextDouble() * 0.5)).toInt.max(1)

  // --- compression --------------------------------------------------------

  /** Preflight compression: when the last real prompt-token count crosses
    * `compression.threshold` × context window, prune old tool results
    * (no-LLM phase 1) and, if a large middle remains, summarize it via one
    * model call — protecting the head, the recent tail, and the last real
    * user message.
    */
  /** Drain the steer buffer, injecting any queued messages as a single user
    * turn so the model sees them before the next iteration. */
  private def drainSteer(callbacks: TurnCallbacks): Unit < (Sync & Async) =
    val msgs = scala.collection.mutable.ListBuffer[String]()
    var m = steerBox.poll()
    while m != null do
      msgs += m
      m = steerBox.poll()
    if msgs.isEmpty then Sync.defer(())
    else
      val steerMsg = Message.user(msgs.mkString("\n"))
      messages = messages :+ steerMsg
      callbacks.onStatus(s"steering: ${msgs.mkString("; ").take(120)}")
        .andThen(store.appendMessage(sid, steerMsg))

  private def maybeCompress(callbacks: TurnCallbacks): Unit < (Sync & Async) =
    val forced        = forceCompress
    forceCompress     = false // consumed once, whether or not we act on it
    val contextLength = runtime.contextLength.getOrElse(200_000)
    val threshold     = Compression.triggerAt(
      contextLength, config.compressionThreshold, config.compressionThresholdTokens)
    val due           = config.compressionEnabled && lastPromptTokens >= threshold
    // `/compress` (forced) overrides the threshold and the enabled flag, but we
    // still need enough history for a summary to be worthwhile.
    if messages.length < 8 || (!due && !forced) then ()
    else
      val reason = if forced && !due then "compressing history (requested)" else "context pressure: compressing history"
      val open   = traceBegin("compress")
      ObsLog.debug(s"→ compress · $reason (${messages.length} messages)", traceMaybe).andThen {
        callbacks.onStatus(reason).andThen {
          val pruned = Compression.pruneOldToolResults(messages, config.protectLastN)
          Compression.summarizeMiddle(pruned, config, runtime, sid, store).map { compressed =>
            messages = compressed
            lastPromptTokens = 0 // re-measured on the next response
          }.andThen(Sync.defer(traceEnd(open, false, List(Otlp.Attr.I("messages_after", messages.length.toLong)))))
            .andThen(ObsLog.debug(s"← compress · now ${messages.length} messages", traceMaybe))
        }
      }

  /** Close the current `llm.call` span, record its latency (and TTFT), and log
    * the round's outcome. */
  private def closeLlmSpan(
      open: TraceContext.Open,
      result: Result[AgentError, TurnResponse]
  ): Unit < (Sync & Async) =
    val ms   = java.lang.System.currentTimeMillis() - llmCallStartMs
    val ttft = if llmFirstTokenMs > 0L then llmFirstTokenMs - llmCallStartMs else -1L
    val base = List(
      Otlp.Attr.S("provider", runtime.providerSlug),
      Otlp.Attr.S("model", runtime.model),
      Otlp.Attr.I("duration_ms", ms))
    val ttftAttr = if ttft >= 0 then List(Otlp.Attr.I("ttft_ms", ttft)) else Nil
    val (error, extra, note) = result match
      case Result.Success(resp) =>
        (false,
          List(Otlp.Attr.I("tokens.input", resp.usage.inputTokens),
               Otlp.Attr.I("tokens.output", resp.usage.outputTokens)),
          s"in=${resp.usage.inputTokens} out=${resp.usage.outputTokens}")
      case Result.Failure(AgentError.Interrupted) => (false, List(Otlp.Attr.S("result", "interrupted")), "interrupted")
      case _                                      => (true, List(Otlp.Attr.S("result", "error")), "error")
    Sync.defer(traceEnd(open, error, base ++ ttftAttr ++ extra))
      .andThen(Metrics.llmCall(ms))
      .andThen(if ttft >= 0 then Metrics.ttft(ttft) else Sync.defer(()))
      .andThen(ObsLog.debug(
        s"← llm.call · ${ms}ms${if ttft >= 0 then s" ttft=${ttft}ms" else ""} · $note", traceMaybe))

  private def finishTurn(text: String, reason: String, interrupted: Boolean): TurnResult < (Sync & Async) =
    val turnMs = java.lang.System.currentTimeMillis() - turnStartMs
    val rootAttrs = List(
      Otlp.Attr.S("exit_reason", reason),
      Otlp.Attr.I("api_calls", apiCalls.toLong),
      Otlp.Attr.I("tokens.input", totalUsage.inputTokens),
      Otlp.Attr.I("tokens.output", totalUsage.outputTokens),
      Otlp.Attr.I("duration_ms", turnMs),
      Otlp.Attr.B("interrupted", interrupted))
    Sync.defer {
      traceEnd(rootOpen, reason.startsWith("error"), rootAttrs)
      lastTraceCtx = traceMaybe // expose the completed tree to /trace + console
      ()
    }.andThen(ObsLog.info(
        s"turn end · reason=$reason apiCalls=$apiCalls in=${totalUsage.inputTokens} out=${totalUsage.outputTokens} ${turnMs}ms",
        traceMaybe))
      .andThen(store.updateMeta(sid)(m =>
        m.copy(messageCount = messages.length, apiCalls = apiCalls, usage = totalUsage)))
      .andThen(Metrics.turnCompleted(reason, apiCalls.toLong, totalUsage.inputTokens, totalUsage.outputTokens, turnMs))
      .andThen(maybeExportObs())
      .andThen(TurnResult(text, reason, apiCalls, totalUsage, interrupted))

  /** Fire-and-forget OTLP export of this turn's traces, the cumulative metric
    * snapshot, and any buffered logs — gated by `monitoring.export.otlp.*`,
    * fail-open, content-free. */
  private def maybeExportObs(): Unit < (Sync & Async) =
    if !config.otlpAnyEnabled || turnTrace == null then Sync.defer(())
    else
      val tc = turnTrace
      Fiber.initUnscoped(Monitor.exportAll(config, tc)).unit
end Agent

/** Strict role-alternation repair (upstream `repair_message_sequence`):
  * merge consecutive user messages, stub orphan tool calls, drop orphan
  * tool results — providers reject violations with empty responses.
  */
object Alternation:

  def repair(messages: List[Message]): List[Message] =
    val stubbed = pairToolCalls(messages)
    mergeConsecutiveUsers(stubbed)

  private def pairToolCalls(messages: List[Message]): List[Message] =
    val out = List.newBuilder[Message]
    var i   = 0
    while i < messages.length do
      val m = messages(i)
      m.role match
        case Role.Assistant =>
          val calls = m.content.collect { case tu: Content.ToolUse => tu }
          if calls.isEmpty then out += m
          else
            val next       = messages.lift(i + 1)
            val resultIds  = next.filter(_.role == Role.Tool).toList
              .flatMap(_.content.collect { case tr: Content.ToolResult => tr.toolUseId }).toSet
            val missing = calls.filterNot(c => resultIds.contains(c.id))
            out += m
            if next.exists(_.role == Role.Tool) then
              val enriched = next.get.copy(content =
                next.get.content ++ missing.map(c =>
                  Content.ToolResult(c.id, "[no result recorded]", isError = true)
                )
              )
              out += enriched
              i += 1
            else if missing.nonEmpty then
              out += Message.toolResults(missing.map(c =>
                Content.ToolResult(c.id, "[no result recorded]", isError = true)
              ))
        case Role.Tool =>
          // Orphan tool results (no preceding assistant tool call) are dropped.
          val prev = messages.lift(i - 1)
          val callIds = prev.filter(_.role == Role.Assistant).toList
            .flatMap(_.content.collect { case tu: Content.ToolUse => tu.id }).toSet
          val kept = m.content.collect { case tr: Content.ToolResult if callIds.contains(tr.toolUseId) => tr }
          if kept.nonEmpty && prev.exists(_.role == Role.Assistant) then () // already emitted above
          else if kept.nonEmpty then out += m.copy(content = kept)
        case _ => out += m
      i += 1
    out.result()
  end pairToolCalls

  private def mergeConsecutiveUsers(messages: List[Message]): List[Message] =
    messages.foldLeft(List.empty[Message]) { (acc, m) =>
      (acc.lastOption, m.role) match
        case (Some(prev), Role.User) if prev.role == Role.User =>
          val mergedText = List(prev, m).flatMap(_.content.collect { case Content.Text(t) => t }).mkString("\n\n")
          val otherContent = (prev.content ++ m.content).filterNot(_.isInstanceOf[Content.Text])
          acc.init :+ Message(Role.User, Content.Text(mergedText) :: otherContent)
        case _ => acc :+ m
    }
end Alternation

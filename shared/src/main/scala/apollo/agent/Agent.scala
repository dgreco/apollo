package apollo.agent

import apollo.core.*
import apollo.provider.*
import apollo.session.{SessionMeta, SessionStore}
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
    exitReason: String, // text_response | max_iterations | interrupted | error(...)
    apiCalls: Int,
    usage: Usage,
    interrupted: Boolean
)

enum AgentError:
  case Provider(error: ProviderError)
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
    store.appendMessage(sid, userMessage).andThen {
      loop(effectiveSystemPrompt, toolNames, callbacks, iterationsThisTurn = 0, graceUsed = false)
    }

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
        maybeCompress(callbacks).andThen {
          messages = Alternation.repair(messages)
          val request = TurnRequest(
            runtime = runtime,
            systemPrompt = systemPrompt,
            messages = messages,
            tools = if grace then Nil else ToolRegistry.definitions(toolNames, toolCtx)
          )
          callWithFallback(request, callbacks).map {
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
              store.appendMessage(sid, response.message).andThen {
                if toolUses.isEmpty then
                  val text = response.message.content.collect { case Content.Text(t) => t }.mkString("\n")
                  finishTurn(text, "text_response", interrupted = false)
                else
                  runToolRound(toolUses, callbacks).map { results =>
                    val resultMsg = Message.toolResults(results)
                    messages = messages :+ resultMsg
                    store.appendMessage(sid, resultMsg).andThen {
                      loop(systemPrompt, toolNames, callbacks, iterationsThisTurn + 1, graceUsed || grace)
                    }
                  }
              }
          }
        }
  end loop

  private def runToolRound(
      toolUses: List[Content.ToolUse],
      callbacks: TurnCallbacks
  ): List[Content.ToolResult] < (Sync & Async) =
    Kyo.foreach(toolUses) { tu =>
      if interruptFlag.get then
        // Keep tool_call/result pairing intact on interrupt.
        Content.ToolResult(tu.id, s"[Tool execution cancelled — ${tu.name} was skipped due to user interrupt]", true)
      else
        callbacks.onToolStart(tu.name, tu.arguments.take(200)).andThen {
          ToolRegistry.dispatch(tu.name, tu.arguments, toolCtx).map { (output, isError) =>
            callbacks.onToolComplete(tu.name, output.take(300), isError)
              .andThen(Content.ToolResult(tu.id, output, isError))
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
          case Nil => Result.fail(AgentError.Provider(ProviderError.Network("all providers failed")))
          case rt :: rest =>
            callWithRetries(request, callbacks, attempt = 0, rt).map {
              case Result.Failure(AgentError.Provider(err)) if rest.nonEmpty && !interruptFlag.get =>
                callbacks.onStatus(
                  s"provider ${rt.providerSlug} failed (${err.getMessage.take(80)}); " +
                    s"falling over to ${rest.head.providerSlug}")
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
    val events: StreamEvent => Unit < (Sync & Async) =
      case StreamEvent.TextDelta(t)        => callbacks.onTextDelta(t)
      case StreamEvent.ThinkingDelta(t)    => callbacks.onThinkingDelta(t)
      case StreamEvent.ToolUseStarted(_, n) => callbacks.onStatus(s"tool: $n")
      case _                                => ()

    val transport = WireTransport.forMode(rt.apiMode) match
      case Result.Success(t) => t
      case Result.Failure(e) => return Result.fail(AgentError.Provider(e))
      case _                 => return Result.fail(AgentError.Provider(ProviderError.Protocol("no transport")))

    interruptible(transport.streamTurn(request.copy(runtime = rt))(events)).map {
      case Result.Success(resp) => Result.succeed(resp)
      case Result.Failure(AgentError.Provider(err)) if err.retryable && attempt + 1 < config.apiMaxRetries =>
        val delay = backoffSeconds(attempt)
        callbacks.onStatus(s"provider error (${err.getMessage.take(120)}); retry ${attempt + 2}/${config.apiMaxRetries} in ${delay}s")
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
      call: TurnResponse < (Sync & Async & Abort[ProviderError])
  ): Result[AgentError, TurnResponse] < (Sync & Async) =
    def watcher: Result[AgentError, TurnResponse] < (Sync & Async) =
      Async.sleep(200.millis).andThen {
        if interruptFlag.get then Result.fail(AgentError.Interrupted)
        else watcher
      }
    val main: Result[AgentError, TurnResponse] < (Sync & Async) =
      Abort.run[ProviderError](call).map {
        case Result.Success(r) => Result.succeed(r)
        case Result.Failure(e) => Result.fail(AgentError.Provider(e))
        case Result.Panic(e)   => Result.fail(AgentError.Provider(ProviderError.Network(String.valueOf(e.getMessage))))
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
  private def maybeCompress(callbacks: TurnCallbacks): Unit < (Sync & Async) =
    val forced        = forceCompress
    forceCompress     = false // consumed once, whether or not we act on it
    val contextLength = runtime.contextLength.getOrElse(200_000)
    val threshold     = (config.compressionThreshold * contextLength).toLong
    val due           = config.compressionEnabled && lastPromptTokens >= threshold
    // `/compress` (forced) overrides the threshold and the enabled flag, but we
    // still need enough history for a summary to be worthwhile.
    if messages.length < 8 || (!due && !forced) then ()
    else
      val reason = if forced && !due then "compressing history (requested)" else "context pressure: compressing history"
      callbacks.onStatus(reason).andThen {
        val pruned = Compression.pruneOldToolResults(messages, config.protectLastN)
        Compression.summarizeMiddle(pruned, config, runtime, sid, store).map { compressed =>
          messages = compressed
          lastPromptTokens = 0 // re-measured on the next response
        }
      }

  private def finishTurn(text: String, reason: String, interrupted: Boolean): TurnResult < (Sync & Async) =
    store.updateMeta(sid)(m =>
      m.copy(messageCount = messages.length, apiCalls = apiCalls, usage = totalUsage)
    ).andThen(TurnResult(text, reason, apiCalls, totalUsage, interrupted))
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

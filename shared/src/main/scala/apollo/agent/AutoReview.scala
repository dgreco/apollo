package apollo.agent

import apollo.core.*
import apollo.provider.ResolvedRuntime
import apollo.session.{SessionMeta, SessionStore}
import apollo.tools.ToolContext
import kyo.*

/** Background self-improvement: after a cadence of turns, fork a reviewer agent
  * that *actually saves* durable learnings (memory / skills), rather than merely
  * nudging the main model to do so. Mirrors Hermes's post-turn auto-review.
  *
  * The cadence and prompt are pure/testable; `runOnce` builds and runs the
  * reviewer (fire-and-forget from the caller's fiber).
  */
object AutoReview:

  /** Reviewer iteration cap — it only needs a couple of memory/skill calls. */
  val maxIterations = 6

  /** Turns elapsed since the last review fired. */
  final case class State(turnsSinceReview: Int = 0)

  /** Advances the cadence by one turn; returns the next state and whether a
    * review is due now (every `interval` turns). */
  def tick(state: State, interval: Int): (State, Boolean) =
    val n = state.turnsSinceReview + 1
    if interval > 0 && n >= interval then (State(0), true)
    else (State(n), false)

  /** The subset of a toolset that a saving reviewer should wield. */
  def reviewToolNames(available: List[String]): List[String] =
    available.filter(t => t == "memory" || t == "skill_manage")

  /** The instruction handed to the reviewer (as a user turn) over the restored
    * conversation. Deliberately conservative: save only genuinely durable,
    * reusable learnings, and do nothing when there is nothing worth keeping. */
  def reviewInstruction(hasMemory: Boolean, hasSkill: Boolean): String =
    val actions = List(
      if hasMemory then Some("save a concise, durable fact with the `memory` tool") else None,
      if hasSkill then Some("capture a reusable procedure with the `skill_manage` tool") else None
    ).flatten
    "You are a background reviewer of the conversation above. Identify at most one " +
      "genuinely durable, reusable learning — a stable user preference, a project " +
      "fact, or a repeatable procedure — and " + actions.mkString(", or ") + ". " +
      "Be conservative: if nothing is clearly worth persisting, reply with the single " +
      "word NONE and save nothing. Do not restate the conversation."

  /** Builds a reviewer agent seeded with `history`, restricts it to the saving
    * tools, and runs exactly one turn. Returns the work effect so the caller can
    * fork it with `Fiber.initUnscoped`. No-op when there is nothing to review. */
  def runOnce(
      runtime: ResolvedRuntime,
      ctx: ToolContext,
      store: SessionStore,
      history: List[Message],
      reviewTools: List[String]
  ): Unit < (Sync & Async) =
    if history.isEmpty || reviewTools.isEmpty then Sync.defer(())
    else
      Sync.defer(java.time.Instant.now()).map { now =>
        val id       = store.newSessionId(now)
        val flag     = new java.util.concurrent.atomic.AtomicBoolean(false)
        val reviewCtx = ctx.copy(platform = "auto-review", sessionId = id, delegate = Absent)
        val reviewer = new Agent(runtime, reviewCtx, store, id, Present(maxIterations), flag)
        reviewer.restore(history)
        val instruction = reviewInstruction(
          reviewTools.contains("memory"), reviewTools.contains("skill_manage"))
        store.create(SessionMeta(
            id = id, title = Present("auto-review"), platform = "auto-review",
            model = runtime.model, provider = runtime.providerSlug,
            startedAt = now.toEpochMilli / 1000.0, endedAt = Absent, cwd = ctx.cwd.toString,
            messageCount = history.length, apiCalls = 0, usage = Usage.zero))
          .andThen(SystemPrompt.build(SystemPrompt.Input(
            ctx.config, ctx.paths, ctx.skills, ctx.cwd, "auto-review",
            runtime.model, runtime.providerSlug, reviewTools)))
          .map(system => reviewer.runTurn(Message.user(instruction), system, reviewTools, TurnCallbacks()).unit)
      }
end AutoReview

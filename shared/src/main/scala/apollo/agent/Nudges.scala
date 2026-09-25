// SPDX-FileCopyrightText: 2026 David Greco <greco@acm.org>
//
// SPDX-License-Identifier: Apache-2.0

package apollo.agent

import kyo.*

/** Hermes's self-improvement nudges: at a configured cadence the agent is
  * reminded to persist durable knowledge (`memory`) and to capture reusable
  * procedures (`skill_manage`). Hermes counts user turns against
  * `memory.nudge_interval` (default 10) and `skills.creation_nudge_interval`
  * (default 15), gated on the relevant tool being present, resets the counter
  * when it fires, and hydrates the counter from prior history on resume
  * (`turns_since = prior_user_turns % interval`).
  *
  * The decision is pure and stateful-by-value so it is unit-testable without
  * a live model; the `Agent` holds the `State` and appends the returned
  * reminder to that turn's system prompt (ephemeral — the transcript stays
  * clean, matching Hermes's clean-user-message split).
  */
object Nudges:

  /** Per-session counters (turns since each nudge last fired). */
  final case class State(turnsSinceMemory: Int = 0, turnsSinceSkill: Int = 0)

  /** What's enabled for this session — resolved from config + the active
    * toolset so a nudge never points at a tool the model doesn't have.
    */
  final case class Settings(
      memoryInterval: Int,
      skillInterval: Int,
      memoryEnabled: Boolean,
      hasMemoryTool: Boolean,
      hasSkillTool: Boolean
  ):
    def memoryGated: Boolean = memoryEnabled && hasMemoryTool && memoryInterval > 0
    def skillGated: Boolean  = hasSkillTool && skillInterval > 0

  /** Advances one user turn. Returns the new state and, when a nudge is due,
    * the reminder text to append to the system prompt for this turn.
    */
  def tick(state: State, s: Settings): (State, Maybe[String]) =
    val (memCount, memFire) =
      if !s.memoryGated then (state.turnsSinceMemory, false)
      else
        val n = state.turnsSinceMemory + 1
        if n >= s.memoryInterval then (0, true) else (n, false)
    val (skillCount, skillFire) =
      if !s.skillGated then (state.turnsSinceSkill, false)
      else
        val n = state.turnsSinceSkill + 1
        if n >= s.skillInterval then (0, true) else (n, false)
    (State(memCount, skillCount), reminder(memFire, skillFire))

  /** Resume hydration: seed the counters from the prior user-turn count so a
    * resumed session doesn't restart the cadence from zero (Hermes parity).
    */
  def hydrate(priorUserTurns: Int, s: Settings): State =
    State(
      turnsSinceMemory = if s.memoryGated then priorUserTurns % s.memoryInterval else 0,
      turnsSinceSkill  = if s.skillGated then priorUserTurns % s.skillInterval else 0
    )

  private val memoryReminder =
    "[self-improvement] Several turns have passed since you last saved to memory. If this " +
      "conversation surfaced durable facts about the user or project, record them now with the " +
      "memory tool before continuing — don't let them slip away."

  private val skillReminder =
    "[self-improvement] If you've worked out a reusable procedure or a non-obvious fix, capture it " +
      "as a skill with skill_manage so future sessions benefit."

  /** The reminder block for the fired nudges, or Absent when none fired. */
  def reminder(memory: Boolean, skill: Boolean): Maybe[String] =
    val parts = List(
      if memory then Some(memoryReminder) else None,
      if skill then Some(skillReminder) else None
    ).flatten
    if parts.isEmpty then Absent else Present(parts.mkString("\n\n"))
end Nudges

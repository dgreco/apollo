// SPDX-FileCopyrightText: 2026 David Greco <greco@acm.org>
//
// SPDX-License-Identifier: Apache-2.0

package apollo.agent

import kyo.Absent

class NudgesSuite extends munit.FunSuite:

  private val full = Nudges.Settings(
    memoryInterval = 10, skillInterval = 15,
    memoryEnabled = true, hasMemoryTool = true, hasSkillTool = true)

  /** Runs `n` ticks, returning the turn numbers (1-based) on which each nudge fired. */
  private def fireTurns(s: Nudges.Settings, n: Int): (List[Int], List[Int]) =
    var st = Nudges.State()
    val mem = List.newBuilder[Int]
    val skl = List.newBuilder[Int]
    for turn <- 1 to n do
      val (next, reminder) = Nudges.tick(st, s)
      st = next
      reminder.foreach { r =>
        if r.contains("saved to memory") then mem += turn
        if r.contains("as a skill") then skl += turn
      }
    (mem.result(), skl.result())

  test("memory nudge fires exactly on the interval and repeats") {
    val (mem, skill) = fireTurns(full, 30)
    assertEquals(mem, List(10, 20, 30))
    assertEquals(skill, List(15, 30))
  }

  test("interval 0 disables a nudge") {
    val (mem, _) = fireTurns(full.copy(memoryInterval = 0), 40)
    assertEquals(mem, Nil)
  }

  test("a nudge is suppressed when its tool is absent") {
    val noMem = full.copy(hasMemoryTool = false)
    assertEquals(fireTurns(noMem, 25)._1, Nil)
    val noSkill = full.copy(hasSkillTool = false)
    assertEquals(fireTurns(noSkill, 30)._2, Nil)
  }

  test("memory nudge is suppressed when memory is disabled in config") {
    assertEquals(fireTurns(full.copy(memoryEnabled = false), 25)._1, Nil)
  }

  test("both reminders combine when they fire on the same turn") {
    // interval 3 (memory) and 3 (skill) → both fire on turn 3.
    val s = full.copy(memoryInterval = 3, skillInterval = 3)
    var st = Nudges.State()
    val (a, r1) = Nudges.tick(st, s); st = a
    val (b, r2) = Nudges.tick(st, s); st = b
    val (_, r3) = Nudges.tick(st, s)
    assertEquals(r1, Absent)
    assertEquals(r2, Absent)
    assert(r3.exists(t => t.contains("memory") && t.contains("skill")), r3.toString)
  }

  test("hydrate seeds the counter from prior user turns, modulo the interval") {
    // 23 prior turns, interval 10 → 3 since last fire, so it next fires in 7 turns (turn 7).
    val st = Nudges.hydrate(23, full)
    assertEquals(st.turnsSinceMemory, 3)
    assertEquals(st.turnsSinceSkill, 23 % 15) // 8
    // Continuing from there, the memory nudge fires 7 ticks later.
    var s = st
    val fired = (1 to 10).flatMap { turn =>
      val (next, r) = Nudges.tick(s, full); s = next
      if r.exists(_.contains("saved to memory")) then Some(turn) else None
    }
    assertEquals(fired.toList, List(7))
  }

  test("hydrate is a no-op for ungated nudges") {
    val st = Nudges.hydrate(99, full.copy(memoryInterval = 0, hasSkillTool = false))
    assertEquals(st, Nudges.State(0, 0))
  }

  test("reminder text builder") {
    assertEquals(Nudges.reminder(false, false), Absent)
    assert(Nudges.reminder(true, false).exists(_.contains("memory tool")))
    assert(Nudges.reminder(false, true).exists(_.contains("skill_manage")))
  }
end NudgesSuite

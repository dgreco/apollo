// SPDX-FileCopyrightText: 2026 David Greco <greco@acm.org>
//
// SPDX-License-Identifier: Apache-2.0

package apollo.tools

import kyo.*

/** computer_use: the pure arg/directive builders. The screencapture/cliclick
  * subprocesses are macOS-only and live (not run in CI). */
class ComputerUseSuite extends munit.FunSuite:

  test("screencaptureArgs") {
    assertEquals(ComputerUse.screencaptureArgs("/tmp/s.png"), List("screencapture", "-x", "/tmp/s.png"))
  }

  test("cliclickDirective maps each action; validates coordinates/args") {
    assertEquals(ComputerUse.cliclickDirective("move", Present(10L), Present(20L), Absent, Absent), Right("m:10,20"))
    assertEquals(ComputerUse.cliclickDirective("click", Present(1L), Present(2L), Absent, Absent), Right("c:1,2"))
    assertEquals(ComputerUse.cliclickDirective("double_click", Present(3L), Present(4L), Absent, Absent), Right("dc:3,4"))
    assertEquals(ComputerUse.cliclickDirective("right_click", Present(5L), Present(6L), Absent, Absent), Right("rc:5,6"))
    assertEquals(ComputerUse.cliclickDirective("type", Absent, Absent, Present("hello"), Absent), Right("t:hello"))
    assertEquals(ComputerUse.cliclickDirective("key", Absent, Absent, Absent, Present("return")), Right("kp:return"))
    assert(ComputerUse.cliclickDirective("click", Absent, Present(2L), Absent, Absent).isLeft)   // missing x
    assert(ComputerUse.cliclickDirective("type", Absent, Absent, Absent, Absent).isLeft)         // missing text
    assert(ComputerUse.cliclickDirective("nope", Absent, Absent, Absent, Absent).isLeft)
  }
end ComputerUseSuite

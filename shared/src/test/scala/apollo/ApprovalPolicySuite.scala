// SPDX-FileCopyrightText: 2026 David Greco <greco@acm.org>
//
// SPDX-License-Identifier: Apache-2.0

package apollo.tools

import apollo.config.{ApolloConfig, ApolloPaths, EnvChain, Yaml}
import kyo.*

/** `approvals.single_query_mode` and `approvals.timeout` enforcement. */
class ApprovalPolicySuite extends munit.FunSuite:

  private def run[A](v: A < (Sync & Async)): A =
    import AllowUnsafe.embrace.danger
    KyoApp.Unsafe.runAndBlock(20.seconds)(v).getOrThrow

  private def paths() = ApolloPaths(java.nio.file.Files.createTempDirectory("apollo-approval"))

  private def config(yaml: String): ApolloConfig =
    val p = paths()
    ApolloConfig(
      if yaml.isEmpty then Absent else Present(Yaml.parse(yaml).getOrElse(throw new AssertionError("yaml"))),
      EnvChain(Map.empty), p)

  /** A UI that fails the test if the interactive prompt is ever reached. */
  private object NeverPromptUi extends ToolUi:
    def requestApproval(prompt: String): ApprovalDecision < (Sync & Async) =
      throw new AssertionError("interactive prompt should not be reached")
    def clarify(qs: List[ClarifyQuestion]): List[String] < (Sync & Async) = Nil

  /** A UI whose prompt never returns — to exercise the timeout path. */
  private object HangingUi extends ToolUi:
    def requestApproval(prompt: String): ApprovalDecision < (Sync & Async) =
      Async.never.andThen(ApprovalDecision.Once)
    def clarify(qs: List[ClarifyQuestion]): List[String] < (Sync & Async) = Nil

  private val dangerous = "sudo apt update" // gated-dangerous, not hardline

  test("single_query_mode: deny (default) auto-denies without prompting") {
    val cfg = config("") // single_query_mode defaults to deny
    val svc = new ApprovalService(cfg, cfg.paths, "cli", oneShot = false, yoloFlag = false, singleQuery = true)
    val r = run(svc.check(dangerous, NeverPromptUi))
    assert(r.isFailure, r.toString)
    assert(r.failure.getOrElse("").contains("auto-denied"), r.toString)
  }

  test("single_query_mode: approve auto-allows without prompting") {
    val cfg = config("approvals: {single_query_mode: approve}")
    val svc = new ApprovalService(cfg, cfg.paths, "cli", oneShot = false, yoloFlag = false, singleQuery = true)
    assert(run(svc.check(dangerous, NeverPromptUi)).isSuccess)
  }

  test("approvals.timeout auto-denies an unanswered interactive prompt") {
    val cfg = config("approvals: {timeout: 1}")
    val svc = new ApprovalService(cfg, cfg.paths, "cli", oneShot = false, yoloFlag = false)
    val r = run(svc.check(dangerous, HangingUi))
    assert(r.isFailure, r.toString)
    assert(r.failure.getOrElse("").contains("timed out"), r.toString)
  }

  test("safe commands never reach approval regardless of policy") {
    val cfg = config("")
    val svc = new ApprovalService(cfg, cfg.paths, "cli", oneShot = false, yoloFlag = false, singleQuery = true)
    assert(run(svc.check("ls -la", NeverPromptUi)).isSuccess)
  }

  /** A UI that always denies — proves the interactive prompt WAS reached. */
  private object DenyingUi extends ToolUi:
    def requestApproval(prompt: String): ApprovalDecision < (Sync & Async) = ApprovalDecision.Deny
    def clarify(qs: List[ClarifyQuestion]): List[String] < (Sync & Async) = Nil

  test("/yolo runtime toggle bypasses dangerous-command approval") {
    val cfg = config("")
    val svc = new ApprovalService(cfg, cfg.paths, "cli", oneShot = false, yoloFlag = false)
    assert(!svc.yoloEnabled)
    svc.setYolo(true)
    assert(svc.yoloEnabled)
    assert(run(svc.check(dangerous, NeverPromptUi)).isSuccess) // no prompt reached
  }

  test("/yolo can turn OFF a session started with --yolo") {
    val cfg = config("")
    val svc = new ApprovalService(cfg, cfg.paths, "cli", oneShot = false, yoloFlag = true)
    assert(svc.yoloEnabled)
    svc.setYolo(false)
    assert(!svc.yoloEnabled)
    // gating is active again → the prompt is reached and DenyingUi denies
    assert(run(svc.check(dangerous, DenyingUi)).isFailure)
  }

  test("/approvals off bypasses; currentApprovalMode reflects the override") {
    val cfg = config("")
    val svc = new ApprovalService(cfg, cfg.paths, "cli", oneShot = false, yoloFlag = false)
    assertEquals(svc.currentApprovalMode, "manual")
    svc.setApprovalMode("off")
    assertEquals(svc.currentApprovalMode, "off")
    assert(run(svc.check(dangerous, NeverPromptUi)).isSuccess)
  }

  // --- protected instruction files -----------------------------------------

  private def p(s: String) = java.nio.file.Paths.get(s)

  test("an instruction-file write asks even under yolo, and off-list writes don't") {
    val cfg = config("")
    val svc = new ApprovalService(cfg, cfg.paths, "cli", oneShot = false, yoloFlag = true)
    assert(svc.yoloEnabled)
    // Yolo does NOT bypass this gate: the denying UI is reached.
    val denied = run(svc.checkInstructionWrite(List(p("/w/CLAUDE.md")), DenyingUi))
    assert(denied.isFailure, denied.toString)
    assert(denied.failure.getOrElse("").contains("protected instruction file"), denied.toString)
    // An ordinary file never prompts.
    assert(run(svc.checkInstructionWrite(List(p("/w/notes.md")), NeverPromptUi)).isSuccess)
  }

  test("the default basenames match case-insensitively; one hit gates the whole edit") {
    val cfg = config("")
    val svc = new ApprovalService(cfg, cfg.paths, "cli", oneShot = false, yoloFlag = false)
    List("AGENTS.md", "agents.md", "Claude.md", "SOUL.md", ".cursorrules").foreach { name =>
      assertEquals(svc.protectedInstructionHit(List(p(s"/w/$name"))).isEmpty, false, name)
    }
    assertEquals(svc.protectedInstructionHit(List(p("/w/a.txt"), p("/w/AGENTS.md"))), Present("AGENTS.md"))
    assertEquals(svc.protectedInstructionHit(List(p("/w/a.txt"))), Absent)
  }

  test("extra patterns are fnmatch globs on the basename; the gate can be turned off") {
    val extra = config("approvals: {protected_instruction_extra_patterns: [\"*.mdc\"]}")
    val onSvc = new ApprovalService(extra, extra.paths, "cli", oneShot = false, yoloFlag = false)
    assertEquals(onSvc.protectedInstructionHit(List(p("/w/rules.mdc"))), Present("rules.mdc"))

    val off = config("approvals: {protected_instruction_files: false}")
    val offSvc = new ApprovalService(off, off.paths, "cli", oneShot = false, yoloFlag = false)
    assertEquals(offSvc.protectedInstructionHit(List(p("/w/CLAUDE.md"))), Absent)
    assert(run(offSvc.checkInstructionWrite(List(p("/w/CLAUDE.md")), NeverPromptUi)).isSuccess)
  }

  test("an unattended surface refuses an instruction-file write (nobody can answer)") {
    val cfg = config("")
    val svc = new ApprovalService(cfg, cfg.paths, "gateway", oneShot = false, yoloFlag = false)
    assert(run(svc.checkInstructionWrite(List(p("/w/SOUL.md")), UnattendedToolUi)).isFailure)
  }
end ApprovalPolicySuite

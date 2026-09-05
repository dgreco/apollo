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
end ApprovalPolicySuite

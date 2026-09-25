// SPDX-FileCopyrightText: 2026 David Greco <greco@acm.org>
//
// SPDX-License-Identifier: Apache-2.0

package apollo.tools

import apollo.config.{ApolloConfig, ApolloPaths, EnvChain}
import kyo.*

class KanbanSuite extends munit.FunSuite:

  private def run[A](v: A < (Sync & Async & Scope)): A =
    import AllowUnsafe.embrace.danger
    KyoApp.Unsafe.runAndBlock(30.seconds)(Scope.run(v)).getOrThrow

  test("board parse/render round-trips; ops add/move/remove") {
    val cards = List(KanbanBoard.Card("a1", "todo", "write tests"), KanbanBoard.Card("b2", "doing", "build it"))
    assertEquals(KanbanBoard.parse(KanbanBoard.renderFile(cards)), cards)
    val moved = KanbanBoard.move(cards, "a1", "done").get
    assertEquals(moved.find(_.id == "a1").map(_.col), Some("done"))
    assertEquals(KanbanBoard.move(cards, "zz", "done"), None)
    assertEquals(KanbanBoard.remove(cards, "a1").get.map(_.id), List("b2"))
    val board = KanbanBoard.renderBoard(cards)
    assert(board.contains("todo:") && board.contains("blocked:") && board.contains("[a1] write tests"), board)
  }

  test("kanban tool: create/move/complete drive the shared board file") {
    val home = java.nio.file.Files.createTempDirectory("apollo-kanban")
    val out = run {
      val paths  = ApolloPaths(home)
      val config = ApolloConfig(Absent, EnvChain(Map.empty), paths)
      AtomicRef.init(List.empty[TodoItem]).map { todo =>
        val ctx = ToolContext(config = config, paths = paths, cwd = home, platform = "cli", sessionId = "k",
          approvals = new ApprovalService(config, paths, "cli", oneShot = false, yoloFlag = true),
          ui = apollo.tools.UnattendedToolUi, todo = todo,
          skills = new apollo.skills.SkillStore(config, paths))
        for
          c1 <- ToolRegistry.dispatch("kanban", """{"action":"create","text":"first task"}""", ctx).map(_._1)
          c2 <- ToolRegistry.dispatch("kanban", """{"action":"create","text":"second","col":"doing"}""", ctx).map(_._1)
          shown <- ToolRegistry.dispatch("kanban", """{"action":"show"}""", ctx).map(_._1)
        yield (c1, c2, shown)
      }
    }
    val (c1, c2, shown) = out
    assert(c1.contains("created") && c1.contains("todo"), c1)
    assert(c2.contains("doing"), c2)
    assert(shown.contains("first task") && shown.contains("second"), shown)
    // the REPL reads the same file the tool wrote
    val fileCards = KanbanBoard.parse(new String(java.nio.file.Files.readAllBytes(KanbanBoard.boardFile(ApolloPaths(home))), "UTF-8"))
    assertEquals(fileCards.map(_.text).toSet, Set("first task", "second"))

    // complete the first card → moves it to done
    val id = fileCards.find(_.text == "first task").get.id
    val done = run {
      val paths  = ApolloPaths(home)
      val config = ApolloConfig(Absent, EnvChain(Map.empty), paths)
      AtomicRef.init(List.empty[TodoItem]).map { todo =>
        val ctx = ToolContext(config = config, paths = paths, cwd = home, platform = "cli", sessionId = "k",
          approvals = new ApprovalService(config, paths, "cli", oneShot = false, yoloFlag = true),
          ui = apollo.tools.UnattendedToolUi, todo = todo,
          skills = new apollo.skills.SkillStore(config, paths))
        ToolRegistry.dispatch("kanban", s"""{"action":"complete","id":"$id"}""", ctx).map(_._1)
      }
    }
    assert(done.contains(s"moved $id → done"), done)
  }
end KanbanSuite

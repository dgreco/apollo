// SPDX-FileCopyrightText: 2026 David Greco <greco@acm.org>
//
// SPDX-License-Identifier: Apache-2.0

package apollo.tools

import apollo.util.Jx.*
import kyo.*
import kyo.Structure.Value

/** kanban — the agent's view of the shared local board (same file the REPL
  * `/kanban` uses). Consolidates Hermes's `kanban_show/create/complete/block/…`
  * tools into one action-based tool (apollo's idiom for stateful tools). */
object KanbanTool:

  val entries: List[ToolEntry] = List(
    ToolEntry(
      name = "kanban",
      toolset = "kanban",
      description =
        "A local task board (columns: todo, doing, blocked, review, done). actions: " +
          "show; create (text, [col]); move (id, col); complete (id); block (id); unblock (id); remove (id).",
      parametersJson = """{"type":"object","properties":{
        "action":{"type":"string","enum":["show","create","move","complete","block","unblock","remove"]},
        "text":{"type":"string","description":"Card text (create)"},
        "col":{"type":"string","description":"Target column (create/move)"},
        "id":{"type":"string","description":"Card id (move/complete/block/unblock/remove)"}
      },"required":["action"]}""".replaceAll("\n\\s*", ""),
      emoji = "📋",
      handler = handle
    )
  )

  private def handle(args: Value, ctx: ToolContext): ToolOutcome < (Sync & Async) =
    val paths = ctx.paths
    (args / "action").asStr.getOrElse("show") match
      case "show" =>
        KanbanBoard.load(paths).map(cards => ToolOutcome.Ok(KanbanBoard.renderBoard(cards)))
      case "create" =>
        (args / "text").asStr match
          case Absent => ToolOutcome.Error("create requires 'text'")
          case Present(text) =>
            val col = (args / "col").asStr.getOrElse("todo")
            if !KanbanBoard.columns.contains(col) then
              ToolOutcome.Error(s"unknown column '$col' (${KanbanBoard.columns.mkString(", ")})")
            else
              Sync.defer(java.util.UUID.randomUUID.toString.take(4)).map { id =>
                KanbanBoard.load(paths).map { cards =>
                  KanbanBoard.save(paths, KanbanBoard.add(cards, id, col, text))
                    .andThen(ToolOutcome.Ok(s"created [$id] in $col"))
                }
              }
      case "move"     => moveTo(paths, args, (args / "col").asStr.getOrElse(""))
      case "complete" => moveTo(paths, args, "done")
      case "block"    => moveTo(paths, args, "blocked")
      case "unblock"  => moveTo(paths, args, "todo")
      case "remove" =>
        (args / "id").asStr match
          case Absent => ToolOutcome.Error("remove requires 'id'")
          case Present(id) =>
            KanbanBoard.load(paths).map { cards =>
              KanbanBoard.remove(cards, id) match
                case Some(updated) => KanbanBoard.save(paths, updated).andThen(ToolOutcome.Ok(s"removed $id"))
                case None          => ToolOutcome.Error(s"no card $id")
            }
      case other => ToolOutcome.Error(s"unknown action '$other'")

  private def moveTo(paths: apollo.config.ApolloPaths, args: Value, col: String): ToolOutcome < (Sync & Async) =
    (args / "id").asStr match
      case Absent => ToolOutcome.Error("this action requires 'id'")
      case Present(id) =>
        if !KanbanBoard.columns.contains(col) then
          ToolOutcome.Error(s"unknown column '$col' (${KanbanBoard.columns.mkString(", ")})")
        else
          KanbanBoard.load(paths).map { cards =>
            KanbanBoard.move(cards, id, col) match
              case Some(updated) => KanbanBoard.save(paths, updated).andThen(ToolOutcome.Ok(s"moved $id → $col"))
              case None          => ToolOutcome.Error(s"no card $id")
          }
end KanbanTool

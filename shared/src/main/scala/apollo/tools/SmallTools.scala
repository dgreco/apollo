package apollo.tools

import apollo.config.Fs
import apollo.util.Jx
import apollo.util.Jx.*
import kyo.*
import kyo.Structure.Value

/** todo_list — in-memory task planning, the upstream harness schema. */
object TodoTool:

  val entries: List[ToolEntry] = List(
    ToolEntry(
      name = "todo_list",
      toolset = "todo",
      description =
        "Task planning and tracking. Pass the full list of todos to replace it (or merge=true to merge); " +
          "call with no arguments to read the current list.",
      parametersJson = """{"type":"object","properties":{
        "todos":{"type":"array","items":{"type":"object","properties":{
          "id":{"type":"string"},
          "content":{"type":"string"},
          "status":{"type":"string","enum":["pending","in_progress","completed","cancelled"]},
          "parent":{"type":"string"}
        },"required":["id","content","status"]}},
        "merge":{"type":"boolean","default":false}
      }}""".replaceAll("\n\\s*", ""),
      emoji = "📋",
      handler = handle
    )
  )

  private def handle(args: Value, ctx: ToolContext): ToolOutcome < (Sync & Async) =
    (args / "todos").asArr match
      case Absent =>
        ctx.todo.get.map(render)
      case Present(items) =>
        val merge = (args / "merge").asBool.getOrElse(false)
        val parsed = items.toList.flatMap { item =>
          for
            id      <- (item / "id").asStr.toList
            content <- (item / "content").asStr.toList
            status  <- (item / "status").asStr.toList
          yield TodoItem(id, content, status, (item / "parent").asStr)
        }
        val update: List[TodoItem] => List[TodoItem] =
          if merge then existing => existing.filterNot(e => parsed.exists(_.id == e.id)) ++ parsed
          else _ => parsed
        ctx.todo.updateAndGet(update).map(render)

  private def render(todos: List[TodoItem]): ToolOutcome =
    if todos.isEmpty then ToolOutcome.Ok("(todo list empty)")
    else
      val mark = Map("pending" -> "[ ]", "in_progress" -> "[~]", "completed" -> "[x]", "cancelled" -> "[-]")
      ToolOutcome.Ok(todos.map(t => s"${mark.getOrElse(t.status, "[ ]")} ${t.id}: ${t.content}").mkString("\n"))
end TodoTool

/** memory — persistent MEMORY.md / USER.md with char limits and add /
  * replace / remove operations (single or batched).
  */
object MemoryTool:

  val entries: List[ToolEntry] = List(
    ToolEntry(
      name = "memory",
      toolset = "memory",
      description =
        "Persistent memory across sessions. target=memory (agent notes) or user (user profile); " +
          "actions add/replace/remove, or a batched operations array (applied atomically).",
      parametersJson = """{"type":"object","properties":{
        "target":{"type":"string","enum":["memory","user"]},
        "action":{"type":"string","enum":["add","replace","remove"]},
        "content":{"type":"string"},
        "old_text":{"type":"string"},
        "new_text":{"type":"string"},
        "operations":{"type":"array","items":{"type":"object","properties":{
          "action":{"type":"string","enum":["add","replace","remove"]},
          "content":{"type":"string"},
          "old_text":{"type":"string"},
          "new_text":{"type":"string"}
        },"required":["action"]}}
      },"required":["target"]}""".replaceAll("\n\\s*", ""),
      emoji = "🧠",
      handler = handle
    )
  )

  private def handle(args: Value, ctx: ToolContext): ToolOutcome < (Sync & Async) =
    (args / "target").asStr match
      case Absent => ToolOutcome.Error("missing required parameter: target")
      case Present(target) =>
        val (file, limit, enabled) =
          if target == "user" then
            (ctx.paths.userMd, ctx.config.userCharLimit, ctx.config.userProfileEnabled)
          else (ctx.paths.memoryMd, ctx.config.memoryCharLimit, ctx.config.memoryEnabled)
        if !enabled then ToolOutcome.Error(s"$target memory is disabled in config")
        else
          val ops: List[(String, Maybe[String], Maybe[String], Maybe[String])] =
            (args / "operations").asArr match
              case Present(items) =>
                items.toList.map(op =>
                  ((op / "action").asStr.getOrElse(""), (op / "content").asStr,
                   (op / "old_text").asStr, (op / "new_text").asStr.orElse((op / "content").asStr))
                )
              case Absent =>
                List(((args / "action").asStr.getOrElse("add"), (args / "content").asStr,
                      (args / "old_text").asStr, (args / "new_text").asStr.orElse((args / "content").asStr)))
          Fs.readString(file).map { current =>
            applyOps(current.getOrElse(""), ops) match
              case Result.Failure(err) => ToolOutcome.Error(err)
              case Result.Success(updated) =>
                if updated.length > limit then
                  ToolOutcome.Error(
                    s"result would be ${updated.length} chars (limit $limit). Consolidate or remove entries first."
                  )
                else
                  Fs.writeStringAtomic(file, updated)
                    .map(_ => ToolOutcome.Ok(s"$target memory updated (${updated.length}/$limit chars)"))
              case _ => ToolOutcome.Error("memory update failed")
          }
  end handle

  private def applyOps(
      initial: String,
      ops: List[(String, Maybe[String], Maybe[String], Maybe[String])]
  ): Result[String, String] =
    ops.foldLeft(Result.succeed(initial): Result[String, String]) { case (acc, (action, content, oldText, newText)) =>
      acc.flatMap { text =>
        action match
          case "add" =>
            content match
              case Present(c) => Result.succeed(if text.isEmpty then c else s"$text\n$c")
              case Absent     => Result.fail("add requires content")
          case "replace" =>
            (oldText, newText) match
              case (Present(o), Present(n)) =>
                if text.contains(o) then Result.succeed(text.replace(o, n))
                else Result.fail(s"old_text not found: ${o.take(80)}")
              case _ => Result.fail("replace requires old_text and new_text/content")
          case "remove" =>
            oldText.orElse(content) match
              case Present(o) =>
                if text.contains(o) then Result.succeed(text.replace(o, "").replaceAll("\n{3,}", "\n\n").trim)
                else Result.fail(s"text not found: ${o.take(80)}")
              case Absent => Result.fail("remove requires old_text")
          case other => Result.fail(s"unknown action: $other")
      }
    }

  /** System-prompt block for one memory store (empty when unset/disabled). */
  def formatForPrompt(title: String, content: String): String =
    if content.trim.isEmpty then ""
    else s"## $title\n${content.trim}"
end MemoryTool

/** clarify — blocking questions to the user, with choices. */
object ClarifyTool:

  val entries: List[ToolEntry] = List(
    ToolEntry(
      name = "clarify",
      toolset = "clarify",
      description = "Ask the user one or more clarifying questions and wait for answers.",
      parametersJson = """{"type":"object","properties":{
        "questions":{"type":"array","minItems":1,"items":{"type":"object","properties":{
          "question":{"type":"string"},
          "choices":{"type":"array","items":{"type":"string"}},
          "multi_select":{"type":"boolean"}
        },"required":["question"]}}
      },"required":["questions"]}""".replaceAll("\n\\s*", ""),
      emoji = "❓",
      handler = handle
    )
  )

  private def handle(args: Value, ctx: ToolContext): ToolOutcome < (Sync & Async) =
    (args / "questions").asArr match
      case Absent => ToolOutcome.Error("missing required parameter: questions")
      case Present(items) =>
        val questions = items.toList.flatMap { q =>
          (q / "question").asStr.map { text =>
            ClarifyQuestion(
              text,
              (q / "choices").asArr.getOrElse(Chunk.empty).toList.flatMap(_.asStr.toList),
              (q / "multi_select").asBool.getOrElse(false)
            )
          }.toList
        }
        if questions.isEmpty then ToolOutcome.Error("questions must contain at least one question")
        else
          ctx.ui.clarify(questions).map { answers =>
            ToolOutcome.Ok(Jx.render(Jx.obj("responses" -> Jx.arr(answers.map(Jx.str)))))
          }
end ClarifyTool

/** skills_list / skill_view / skill_manage over the SkillStore. */
object SkillsTools:

  val entries: List[ToolEntry] = List(
    ToolEntry(
      name = "skills_list",
      toolset = "skills",
      description = "List available skills, optionally filtered by category.",
      parametersJson = """{"type":"object","properties":{
        "category":{"type":"string","description":"Only list skills in this category"}
      }}""".replaceAll("\n\\s*", ""),
      emoji = "📚",
      handler = (args, ctx) =>
        ctx.skills.scan.map { skills =>
          val filtered = (args / "category").asStr match
            case Present(cat) => skills.filter(_.category == cat)
            case Absent       => skills
          val listing = filtered.sortBy(s => (s.category, s.name)).map { s =>
            Jx.obj("name" -> Jx.str(s.name), "description" -> Jx.str(s.description), "category" -> Jx.str(s.category))
          }
          ToolOutcome.Ok(Jx.render(Jx.obj(
            "success" -> Jx.bool(true),
            "skills"  -> Jx.arr(listing),
            "count"   -> Jx.num(filtered.size),
            "hint"    -> Jx.str("Load a skill with skill_view(name) before using it.")
          )))
        }
    ),
    ToolEntry(
      name = "skill_view",
      toolset = "skills",
      description = "Load a skill's SKILL.md, or a linked file inside it via file_path.",
      parametersJson = """{"type":"object","properties":{
        "name":{"type":"string","description":"Skill name"},
        "file_path":{"type":"string","description":"Relative path inside the skill dir, e.g. references/api.md"}
      },"required":["name"]}""".replaceAll("\n\\s*", ""),
      emoji = "📚",
      maxResultChars = 100_000,
      handler = (args, ctx) =>
        (args / "name").asStr match
          case Absent => ToolOutcome.Error("missing required parameter: name")
          case Present(name) =>
            ctx.skills.view(name, (args / "file_path").asStr).map {
              case Result.Success(text) => ToolOutcome.Ok(text)
              case Result.Failure(err)  => ToolOutcome.Error(err)
              case _                    => ToolOutcome.Error("skill_view failed")
            }
    ),
    ToolEntry(
      name = "skill_manage",
      toolset = "skills",
      description =
        "Create, patch, delete skills or manage their files. Accepts an operations array; each item " +
          "has name + action (create|patch|delete|write_file|remove_file) plus action-specific fields.",
      parametersJson = """{"type":"object","properties":{
        "operations":{"type":"array","minItems":1,"items":{"type":"object","properties":{
          "name":{"type":"string"},
          "action":{"type":"string","enum":["create","patch","delete","write_file","remove_file"]},
          "content":{"type":"string"},
          "category":{"type":"string"},
          "old_string":{"type":"string"},
          "new_string":{"type":"string"},
          "file_path":{"type":"string"},
          "file_content":{"type":"string"}
        },"required":["name","action"]}}
      },"required":["operations"]}""".replaceAll("\n\\s*", ""),
      emoji = "📝",
      handler = (args, ctx) =>
        (args / "operations").asArr match
          case Absent => ToolOutcome.Error("missing required parameter: operations")
          case Present(ops) =>
            Kyo.foreach(ops.toList) { op =>
              ctx.skills.manage(
                action = (op / "action").asStr.getOrElse(""),
                name = (op / "name").asStr.getOrElse(""),
                content = (op / "content").asStr,
                category = (op / "category").asStr,
                oldString = (op / "old_string").asStr,
                newString = (op / "new_string").asStr,
                filePath = (op / "file_path").asStr,
                fileContent = (op / "file_content").asStr
              )
            }.map { results =>
              val failures = results.collect { case Result.Failure(e) => e }
              if failures.nonEmpty then ToolOutcome.Error(failures.mkString("; "))
              else ToolOutcome.Ok(results.collect { case Result.Success(m) => m }.mkString("\n"))
            }
    )
  )
end SkillsTools

/** session_search + delegate_task — thin wrappers over capabilities injected
  * by the agent/session layers through ToolContext.
  */
object SessionSearchTool:
  val entries: List[ToolEntry] = List(
    ToolEntry(
      name = "session_search",
      toolset = "session_search",
      description = "Search past conversation sessions for relevant context.",
      parametersJson = """{"type":"object","properties":{
        "query":{"type":"string","description":"Search terms"},
        "limit":{"type":"integer","default":3,"maximum":10}
      },"required":["query"]}""".replaceAll("\n\\s*", ""),
      emoji = "🔍",
      available = _.sessionSearch.nonEmpty,
      handler = (args, ctx) =>
        ((args / "query").asStr, ctx.sessionSearch) match
          case (Present(query), Present(search)) =>
            search(query, (args / "limit").asLong.map(_.toInt).getOrElse(3).min(10)).map(ToolOutcome.Ok(_))
          case (Absent, _) => ToolOutcome.Error("missing required parameter: query")
          case _           => ToolOutcome.Error("session search is unavailable in this context")
    )
  )

object DelegateTool:
  val entries: List[ToolEntry] = List(
    ToolEntry(
      name = "delegate_task",
      toolset = "delegation",
      description =
        "Spawn isolated subagents for parallel workstreams. Each task gets a fresh agent with its own " +
          "context; results return as summaries. Children cannot call delegate_task, clarify, memory or " +
          "cronjob_manage.",
      parametersJson = """{"type":"object","properties":{
        "tasks":{"type":"array","minItems":1,"items":{"type":"object","properties":{
          "goal":{"type":"string","description":"What the subagent should accomplish"},
          "context":{"type":"string","description":"Background the subagent needs"}
        },"required":["goal"]}},
        "toolsets":{"type":"array","items":{"type":"string"},"description":"Toolsets for the children (default: terminal, file, web)"}
      },"required":["tasks"]}""".replaceAll("\n\\s*", ""),
      emoji = "🔀",
      maxResultChars = 24000,
      available = _.delegate.nonEmpty,
      handler = (args, ctx) =>
        (ctx.delegate, (args / "tasks").asArr) match
          case (Absent, _) => ToolOutcome.Error("delegation is unavailable in this context")
          case (_, Absent) => ToolOutcome.Error("missing required parameter: tasks")
          case (Present(runner), Present(tasks)) =>
            val toolsets = (args / "toolsets").asArr.getOrElse(Chunk.empty).toList.flatMap(_.asStr.toList) match
              case Nil  => List("terminal", "file", "web")
              case sets => sets.filterNot(Set("delegation", "kanban").contains)
            val parsed = tasks.toList.flatMap { t =>
              (t / "goal").asStr.map(g => (g, (t / "context").asStr.getOrElse(""))).toList
            }
            if parsed.isEmpty then ToolOutcome.Error("tasks must contain at least one goal")
            else
              Kyo.foreach(parsed) { (goal, context) =>
                runner.run(goal, context, toolsets).map(result => s"### $goal\n$result")
              }.map(results => ToolOutcome.Ok(results.mkString("\n\n")))
    )
  )
end DelegateTool

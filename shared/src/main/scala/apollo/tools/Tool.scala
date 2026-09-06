package apollo.tools

import apollo.config.{ApolloConfig, ApolloPaths}
import apollo.core.ToolSpec
import apollo.util.Jx
import apollo.util.Jx.*
import kyo.*
import kyo.Structure.Value

/** Outcome of one tool execution. Errors are rendered to the model as the
  * JSON string `{"error": "..."}` capped at 2048 chars, exactly like the upstream harness.
  */
enum ToolOutcome:
  case Ok(output: String)
  case Error(message: String)

object ToolOutcome:
  private val maxErrorChars = 2048

  def render(outcome: ToolOutcome, maxResultChars: Int): (String, Boolean) =
    outcome match
      case ToolOutcome.Ok(output) =>
        val truncated =
          if output.length <= maxResultChars then output
          else output.take(maxResultChars) + s"\n[... output truncated at $maxResultChars chars]"
        (truncated, false)
      case ToolOutcome.Error(message) =>
        (Jx.render(Jx.obj("error" -> Jx.str(message.take(maxErrorChars)))), true)

/** Everything a tool implementation may need at execution time. Capability
  * interfaces (approvals, UI prompts, subagent spawning) are injected so the
  * tool layer stays decoupled from the CLI/gateway hosting it.
  */
final case class ToolContext(
    config: ApolloConfig,
    paths: ApolloPaths,
    cwd: java.nio.file.Path,
    platform: String,
    sessionId: String,
    approvals: ApprovalService,
    ui: ToolUi,
    todo: AtomicRef[List[TodoItem]],
    skills: apollo.skills.SkillStore,
    /** Runs a delegated subagent; wired by the agent layer (a tool cannot
      * import the loop without a cycle).
      */
    delegate: Maybe[DelegateRunner] = Absent,
    /** Searches past sessions; wired by the session layer. */
    sessionSearch: Maybe[(String, Int) => String < (Sync & Async)] = Absent,
    /** Analyzes an image with a vision model; wired by the agent layer. */
    vision: Maybe[VisionRunner] = Absent,
    interruptRequested: () => Boolean < Sync = () => false
)

/** How a tool asks the human something (approval prompts, clarify). The CLI
  * binds this to the terminal; the gateway to platform messages; unattended
  * surfaces to auto-deny.
  */
trait ToolUi:
  /** Returns the user's decision for a dangerous-command approval. */
  def requestApproval(prompt: String): ApprovalDecision < (Sync & Async)
  /** Asks the clarify questions; returns one answer per question. */
  def clarify(questions: List[ClarifyQuestion]): List[String] < (Sync & Async)

enum ApprovalDecision:
  case Once, Session, Always, Deny

final case class ClarifyQuestion(question: String, choices: List[String], multiSelect: Boolean)

final case class TodoItem(id: String, content: String, status: String, parent: Maybe[String])

/** Interface the delegate_task tool uses to spawn child agents. */
trait DelegateRunner:
  def run(goal: String, context: String, toolsets: List[String]): String < (Sync & Async)

/** Interface the vision_analyze tool uses to run a single vision-model call
  * (a tool cannot import the provider layer without a cycle). */
trait VisionRunner:
  def analyze(mediaType: String, base64: String, prompt: String): String < (Sync & Async)

/** One registered tool: wire schema + handler + availability probe. */
final case class ToolEntry(
    name: String,
    toolset: String,
    description: String,
    parametersJson: String,
    emoji: String,
    maxResultChars: Int = 30000,
    available: ToolContext => Boolean = _ => true,
    handler: (Value, ToolContext) => ToolOutcome < (Sync & Async)
):
  def spec: ToolSpec = ToolSpec(name, description, parametersJson)

object ToolRegistry:

  /** All built-in tools. Assembled lazily so tool objects can reference each
    * other's toolsets without initialization-order traps.
    */
  lazy val all: List[ToolEntry] =
    FileTools.entries ++ TerminalTools.entries ++ TodoTool.entries ++ MemoryTool.entries
      ++ SkillsTools.entries ++ ClarifyTool.entries ++ WebTools.entries ++ CronTool.entries
      ++ SessionSearchTool.entries ++ DelegateTool.entries ++ VisionTool.entries
      ++ ToolSearchTool.entries ++ ImageGen.entries

  private lazy val builtinByName: Map[String, ToolEntry] = all.map(t => t.name -> t).toMap

  /** Dynamically registered tools (MCP servers), keyed by toolset
    * (`mcp-<server>`). Registered at startup by the MCP manager; a server
    * re-registration replaces its previous entries.
    */
  private val dynamic = new java.util.concurrent.ConcurrentHashMap[String, List[ToolEntry]]()

  def registerDynamic(toolset: String, entries: List[ToolEntry]): Unit =
    dynamic.put(toolset, entries)
    ()

  def unregisterDynamic(toolset: String): Unit =
    dynamic.remove(toolset)
    ()

  def dynamicEntries: List[ToolEntry] =
    import scala.jdk.CollectionConverters.*
    dynamic.values.asScala.toList.flatten

  /** toolset → tool names, for `Toolsets` resolution of `mcp-*` sets. */
  def dynamicToolsets: Map[String, List[String]] =
    import scala.jdk.CollectionConverters.*
    dynamic.asScala.view.mapValues(_.map(_.name)).toMap

  /** True when a name is already taken by a built-in or a registered
    * dynamic tool (the MCP manager's cross-registry collision check).
    */
  def nameTaken(name: String): Boolean = byName.contains(name)

  def byName: Map[String, ToolEntry] =
    if dynamic.isEmpty then builtinByName
    else builtinByName ++ dynamicEntries.map(t => t.name -> t)

  /** the upstream harness's legacy tool-name aliases, honored at dispatch. */
  val legacyAliases: Map[String, String] =
    Map("todo" -> "todo_list", "cronjob" -> "cronjob_manage", "process" -> "process_manage")

  /** The wire definitions for a session: selected tools that pass their
    * availability probe.
    */
  def definitions(toolNames: List[String], ctx: ToolContext): List[ToolSpec] =
    toolNames.distinct.flatMap(byName.get).filter(_.available(ctx)).map(_.spec)

  /** Dispatches one call. Unknown tools produce an error result (the model
    * sees it and can recover); handler exceptions are caught and sanitized.
    */
  def dispatch(name: String, argumentsJson: String, ctx: ToolContext): (String, Boolean) < (Sync & Async) =
    val canonical = legacyAliases.getOrElse(name, name)
    byName.get(canonical) match
      case None =>
        ToolOutcome.render(ToolOutcome.Error(s"unknown tool: $name"), 2048)
      case Some(entry) =>
        val args = Jx.parse(argumentsJson) match
          case Result.Success(v) => v
          case _                 => Jx.obj()
        Abort.run[Throwable] {
          Abort.catching[Throwable](entry.handler(args, ctx))
        }.map {
          case Result.Success(outcome) => ToolOutcome.render(outcome, entry.maxResultChars)
          case Result.Failure(e) =>
            ToolOutcome.render(ToolOutcome.Error(sanitize(e.getMessage)), entry.maxResultChars)
          case Result.Panic(e) =>
            ToolOutcome.render(ToolOutcome.Error(sanitize(e.getMessage)), entry.maxResultChars)
        }
  end dispatch

  /** Strips tool-call/system framing tags from exception text before the
    * model sees it (upstream `_sanitize_tool_error`).
    */
  private def sanitize(message: String | Null): String =
    val m = if message == null then "unknown error" else message
    m.replaceAll("(?i)</?(tool_call|system|assistant|user)>", "").take(2000)
end ToolRegistry

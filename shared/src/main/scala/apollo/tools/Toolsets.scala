package apollo.tools

import apollo.config.ApolloConfig

/** Named toolset groups mirroring the upstream harness's `toolsets.py`: atomic capability
  * sets, scenario composites, and per-platform `apollo-*` bundles, with the
  * same resolution semantics (recursive `includes`, cycle tolerance,
  * disabled-subtracted-last).
  *
  * Tools whose backing service isn't implemented in apollo simply have
  * no registry entry; they resolve to nothing rather than erroring, exactly
  * like an upstream tool whose `check_fn` fails.
  */
object Toolsets:

  final case class Def(description: String, tools: List[String] = Nil, includes: List[String] = Nil)

  /** The shared core every CLI/messaging bundle builds on (the upstream harness
    * core-tools list, restricted to what this build implements).
    */
  private val coreTools: List[String] = List(
    "web_search", "web_extract",
    "terminal", "process_manage",
    "read_file", "write_file", "patch", "search_files",
    "skills_list", "skill_view", "skill_manage",
    "todo_list", "memory", "session_search",
    "clarify", "delegate_task", "cronjob_manage",
    "vision_analyze", "execute_code", "tool_search", "image_generate"
  )

  private val webhookSafeTools = List("web_search", "web_extract", "clarify")

  val all: Map[String, Def] = Map(
    // Atomic capability toolsets
    "web"            -> Def("Web search and content extraction", List("web_search", "web_extract")),
    "search"         -> Def("Web search only, no scraping", List("web_search")),
    "terminal"       -> Def("Command execution and process management", List("terminal", "process_manage")),
    "file"           -> Def("File operations: read, write, patch, search",
                            List("read_file", "write_file", "patch", "search_files")),
    "skills"         -> Def("Load skill documents", List("skills_list", "skill_view")),
    "todo"           -> Def("Task planning and tracking", List("todo_list")),
    "memory"         -> Def("Persistent memory across sessions", List("memory")),
    "session_search" -> Def("Search and recall past conversations", List("session_search")),
    "clarify"        -> Def("Ask the user clarifying questions", List("clarify")),
    "delegation"     -> Def("Spawn isolated subagents", List("delegate_task")),
    "cronjob"        -> Def("Schedule and manage automated tasks", List("cronjob_manage")),
    "vision"         -> Def("Analyze images with a vision model", List("vision_analyze")),
    "code_execution" -> Def("Run code snippets", List("execute_code")),
    "tool_search"    -> Def("Discover available tools by keyword", List("tool_search")),
    "image_gen"      -> Def("Generate images from prompts", List("image_generate")),
    "browser"        -> Def("Drive a headless Chrome (navigate, read, click, screenshot)", List("browser")),
    // Composite / scenario toolsets
    "debugging" -> Def("Troubleshooting bundle", Nil, List("terminal", "web", "file")),
    "safe"      -> Def("No terminal access", Nil, List("web")),
    // Platform bundles
    "apollo-cli"      -> Def("Full CLI bundle", coreTools),
    "apollo-cron"     -> Def("Cron bundle", coreTools),
    "apollo-telegram" -> Def("Telegram bundle", coreTools),
    "apollo-discord"  -> Def("Discord bundle", coreTools),
    "apollo-whatsapp" -> Def("WhatsApp bundle", coreTools),
    "apollo-slack"    -> Def("Slack bundle", coreTools),
    "apollo-signal"   -> Def("Signal bundle", coreTools),
    "apollo-api-server" -> Def("API server bundle", coreTools.filterNot(_ == "clarify")),
    "apollo-webhook"  -> Def("Webhook bundle (untrusted input: no execution)", webhookSafeTools),
    "apollo-gateway"  -> Def("All messaging bundles", Nil,
                              List("apollo-telegram", "apollo-discord", "apollo-slack", "apollo-whatsapp"))
  )

  /** Upstream config files name the platform bundles with a legacy prefix;
    * it is translated in `resolve` so an unmodified upstream
    * `platform_toolsets` section keeps selecting the same bundles.
    */
  private val legacyBundlePrefixes = List("hermes-", "jupyter-")

  /** The upstream harness's `_tools`-suffixed legacy aliases. */
  private val legacyAliases: Map[String, String] = Map(
    "web_tools" -> "web", "terminal_tools" -> "terminal", "file_tools" -> "file",
    "skills_tools" -> "skills", "cronjob_tools" -> "cronjob"
  )

  /** MCP servers register per-server toolsets at runtime (`mcp-<server>`,
    * addressable by the plain server name too — the upstream toolset alias).
    */
  private def mcpToolset(name: String): kyo.Maybe[List[String]] =
    val dynamic = ToolRegistry.dynamicToolsets
    kyo.Maybe.fromOption(dynamic.get(name).orElse(dynamic.get(s"mcp-$name")))

  /** Resolves one toolset name to tool names. `all`/`*` unions everything
    * (registered MCP toolsets included); unknown names resolve empty;
    * diamonds/cycles are tolerated.
    */
  def resolve(name: String, visited: Set[String] = Set.empty): List[String] =
    val translated =
      legacyBundlePrefixes.find(name.startsWith) match
        case Some(prefix) => "apollo-" + name.drop(prefix.length)
        case None         => name
    val canonical = legacyAliases.getOrElse(translated, translated)
    if canonical == "all" || canonical == "*" then
      (all.keys.toList.flatMap(resolve(_, visited)) ++ ToolRegistry.dynamicEntries.map(_.name))
        .distinct.sorted
    else if visited.contains(canonical) then Nil
    else
      all.get(canonical) match
        case None => mcpToolset(canonical).getOrElse(Nil)
        case Some(d) =>
          (d.tools ++ d.includes.flatMap(resolve(_, visited + canonical))).distinct.sorted

  /** Selects the tool list for a session, the upstream harness-style: resolve enabled sets
    * (None = everything), then subtract disabled sets LAST so a disable wins
    * over any composite that re-enabled the tool.
    *
    * `mcpDefault` mirrors the upstream registry behavior: registered MCP
    * tools join every session by default and an explicit toolset allowlist
    * (CLI `-t`, delegate_task) restricts them to the servers it names —
    * while `agent.disabled_toolsets` (`mcp-<server>` or the plain server
    * name) still subtracts last either way.
    */
  def select(
      enabled: Option[List[String]],
      disabled: List[String],
      mcpDefault: Boolean = true
  ): List[String] =
    val selected = enabled match
      case None        => all.keys.toList.flatMap(resolve(_)).distinct
      case Some(names) => names.flatMap(resolve(_)).distinct
    val withMcp =
      if mcpDefault then (selected ++ ToolRegistry.dynamicEntries.map(_.name)).distinct
      else selected
    val removed = disabled.flatMap(resolve(_)).toSet
    withMcp.filterNot(removed.contains).sorted

  /** Default bundle for a platform (`apollo-<platform>` when defined). */
  def platformDefault(platform: String): List[String] =
    if all.contains(s"apollo-$platform") then List(s"apollo-$platform") else List("apollo-cli")

  /** Full per-platform selection honoring `platform_toolsets` config and
    * `agent.disabled_toolsets`.
    */
  def forPlatform(config: ApolloConfig, platform: String): List[String] =
    val enabled = config.platformToolsets(platform).map(_.toList) match
      case kyo.Present(names) => names
      case kyo.Absent         => platformDefault(platform)
    select(Some(enabled), config.disabledToolsets)
end Toolsets

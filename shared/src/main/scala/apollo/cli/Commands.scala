package apollo.cli

import apollo.config.{Fs, ApolloConfig, ApolloPaths, Secrets}
import apollo.config.Yaml.*
import apollo.cron.CronStore
import apollo.provider.{CopilotAuth, Profiles, QwenAuth, ResolveError, Runtime, RuntimeOverrides}
import apollo.session.SessionStore
import apollo.skills.SkillStore
import apollo.tools.Toolsets
import kyo.*

/** Non-chat subcommands: model / config / sessions / skills / cron / status
  * / tools / memory.
  */
object Commands:

  def model(args: CliArgs, config: ApolloConfig): Unit < (Sync & Async) =
    Abort.run[ResolveError](Runtime.resolve(config,
      RuntimeOverrides(model = args.model, provider = args.provider))
    ).map {
      case Result.Success(rt) =>
        Console.printLine(
          s"""provider:  ${rt.providerSlug} (${rt.displayName})
             |model:     ${rt.model}
             |base URL:  ${rt.baseUrl}
             |api mode:  ${rt.apiMode}
             |api key:   ${rt.apiKey.map(k => k.take(8) + "…").getOrElse("(none)")}
             |reasoning: ${rt.reasoning.map(r => if r.enabled then r.effort else "none").getOrElse("(unset)")}
             |
             |Known providers: ${Profiles.all.filterNot(_.unsupported).map(_.name).sorted.mkString(", ")}
             |Switch with: apollo -m <model> --provider <name>, or /model in the REPL.""".stripMargin
        )
      case Result.Failure(err) => Console.printLine(Style.red(err.message))
      case Result.Panic(e)     => Console.printLine(Style.red(String.valueOf(e.getMessage)))
    }

  def config(args: CliArgs, config: ApolloConfig, paths: ApolloPaths): Unit < (Sync & Async) =
    args.commandArgs match
      case "path" :: _     => Console.printLine(paths.configYaml.toString)
      case "env-path" :: _ => Console.printLine(paths.dotenv.toString)
      case "get" :: key :: _ =>
        // the upstream harness expands ${VAR} refs at load time, so `config get` shows the
        // expanded value.
        val value = config.root.flatMap(_.path(key.split('.').toIndexedSeq*)) match
          case Present(node) => node.str.map(config.expandVars).getOrElse("(non-scalar value)")
          case Absent        => "(unset)"
        Console.printLine(value)
      case "show" :: _ | Nil =>
        Fs.readString(paths.configYaml).map {
          case Present(text) => Console.printLine(text)
          case Absent        => Console.printLine(s"(no config file at ${paths.configYaml}; run `apollo setup`)")
        }
      case other :: _ =>
        Console.printLine(s"config subcommands: show | get <key> | path | env-path (got: $other)")

  def sessions(args: CliArgs, paths: ApolloPaths): Unit < (Sync & Async) =
    val store = new SessionStore(paths)
    store.list.map { metas =>
      if metas.isEmpty then Console.printLine("no stored sessions")
      else
        val rows = metas.take(30).map { m =>
          val when = java.time.Instant.ofEpochSecond(m.startedAt.toLong).toString.take(16)
          f"${m.id}%-24s $when  ${m.platform}%-9s ${m.model}%-30s ${m.title.getOrElse("")}"
        }
        Console.printLine(rows.mkString("\n"))
    }

  def skills(args: CliArgs, config: ApolloConfig, paths: ApolloPaths): Unit < (Sync & Async) =
    args.commandArgs match
      case "search" :: rest =>
        val query = rest.mkString(" ")
        if query.isEmpty then Console.printLine("usage: apollo skills search <query>")
        else apollo.skills.SkillsHub.search(config, query).map {
          case Result.Failure(err) => Console.printLine(Style.red(err))
          case Result.Success(Nil) => Console.printLine(s"no catalog skills match '$query'")
          case Result.Success(hits) =>
            Console.printLine(hits.map(e => s"${e.name}: ${e.description}\n    ${e.source}").mkString("\n"))
        }
      case "install" :: source :: _ =>
        apollo.skills.SkillsHub.install(paths, source).map {
          case Result.Failure(err)   => Console.printLine(Style.red(s"install failed: $err"))
          case Result.Success(names) => Console.printLine(s"installed: ${names.mkString(", ")} (into ${paths.skillsDir})")
        }
      case "install" :: Nil =>
        Console.printLine("usage: apollo skills install <git-url | owner/repo | local-dir>")
      case _ => listSkills(config, paths)

  private def listSkills(config: ApolloConfig, paths: ApolloPaths): Unit < (Sync & Async) =
    new SkillStore(config, paths).scan.map { skills =>
      if skills.isEmpty then
        Console.printLine(s"no skills installed (drop agentskills.io-format dirs under ${paths.skillsDir})")
      else
        Console.printLine(
          skills.groupBy(_.category).toList.sortBy(_._1).map { (cat, items) =>
            s"$cat:\n" + items.sortBy(_.name).map(s => s"  ${s.name}: ${s.description}").mkString("\n")
          }.mkString("\n")
        )
    }

  def cron(args: CliArgs, config: ApolloConfig, paths: ApolloPaths): Unit < (Sync & Async) =
    args.commandArgs match
      case "run-scheduler" :: _ =>
        Console.printLine("cron scheduler tick loop starting (Ctrl-C to stop)")
          .andThen(apollo.mcp.McpManager.start(config, paths,
            java.nio.file.Paths.get(".").toAbsolutePath.normalize, kyo.Absent, Cli.version))
          .andThen(apollo.cron.Scheduler.runLoop(config, paths))
      case _ =>
        new CronStore(paths).load.map { jobs =>
          if jobs.isEmpty then Console.printLine("no scheduled jobs (create them in chat via cronjob_manage)")
          else
            Console.printLine(jobs.map { j =>
              val next = j.nextRunAt.map(t => java.time.Instant.ofEpochSecond(t.toLong).toString).getOrElse("-")
              s"${j.id}  [${j.state}]  ${j.scheduleDisplay}  next=$next  ${j.name}"
            }.mkString("\n"))
        }

  def status(config: ApolloConfig, paths: ApolloPaths): Unit < (Sync & Async) =
    for
      configExists <- Fs.exists(paths.configYaml)
      envExists    <- Fs.exists(paths.dotenv)
      resolved     <- Abort.run[ResolveError](Runtime.resolve(config, RuntimeOverrides()))
      skillCount   <- new SkillStore(config, paths).scan.map(_.length)
      _ <- Console.printLine(
        s"""home:         ${paths.home}
           |config.yaml:  ${if configExists then "present" else "missing (run `apollo setup`)"}
           |.env:         ${if envExists then "present" else "missing"}
           |provider:     ${resolved match
              case Result.Success(rt) => s"${rt.providerSlug} / ${rt.model} — ok"
              case Result.Failure(e)  => Style.red(e.message)
              case _                  => Style.red("resolution failed")}
           |skills:       $skillCount installed
           |sessions dir: ${paths.home.resolve("scala-state")}""".stripMargin
      )
    yield ()

  def tools(config: ApolloConfig): Unit < (Sync & Async) =
    val lines = Toolsets.all.toList.sortBy(_._1).map { (name, d) =>
      val tools = Toolsets.resolve(name)
      s"$name — ${d.description}\n  ${tools.mkString(", ")}"
    }
    Console.printLine(lines.mkString("\n"))

  /** `apollo mcp [list|test <name>]` — the read-only subset of the upstream
    * `mcp` subcommand (add/remove/configure are manual `config.yaml` edits
    * in this build, which stays drop-in compatible).
    */
  def mcp(args: CliArgs, config: ApolloConfig, paths: ApolloPaths): Unit < (Sync & Async) =
    val cwd = java.nio.file.Paths.get(".").toAbsolutePath.normalize
    val (servers, warnings) = apollo.mcp.McpConfig.load(config, cwd)
    args.commandArgs match
      case "test" :: name :: _ =>
        servers.find(_.name == name) match
          case None =>
            Console.printLine(s"no enabled MCP server named '$name' in mcp_servers (apollo mcp list)")
          case Some(cfg) =>
            Console.printLine(s"probing '${cfg.name}' (${cfg.command.getOrElse(cfg.url.getOrElse("?"))})...")
              .andThen(apollo.mcp.McpManager.probe(cfg, config, paths, Cli.version))
              .map {
                case kyo.Result.Success(tools) =>
                  val lines = tools.map((n, d) => s"  $n${if d.isEmpty then "" else s" — ${d.take(80)}"}")
                  Console.printLine(s"ok: ${tools.length} tool(s)\n${lines.mkString("\n")}")
                case kyo.Result.Failure(err) => Console.printLine(Style.red(err))
                case kyo.Result.Panic(e)     => Console.printLine(Style.red(String.valueOf(e.getMessage)))
              }
      case ("login" | "reauth") :: name :: _ =>
        servers.find(_.name == name) match
          case None =>
            Console.printLine(s"no enabled MCP server named '$name' in mcp_servers (apollo mcp list)")
          case Some(cfg) if !cfg.usesOAuth =>
            Console.printLine(s"'$name' is not configured for OAuth (set auth: oauth on the server)")
          case Some(cfg) =>
            val store = new apollo.mcp.McpOAuthStore(paths)
            val reauth = args.commandArgs.headOption.contains("reauth")
            val prep = if reauth then store.remove(name) else Sync.defer(())
            prep.andThen(PlatformEditor.create).map { editor =>
              // Generous budget: the discovery/exchange requests plus the
              // interactive browser wait (upstream's callback timeout is 300s).
              Console.printLine(s"authorizing '$name' (${cfg.url.getOrElse("?")})…")
                .andThen(apollo.mcp.McpOAuth.login(cfg, store, editor, 300.seconds))
                .map {
                  case kyo.Result.Success(scope) =>
                    Console.printLine(Style.gold(s"✓ authorized '$name' (scope: $scope). " +
                      "Tokens cached; restart the gateway or start a new session to use it."))
                  case kyo.Result.Failure(err) => Console.printLine(Style.red(s"login failed: $err"))
                  case kyo.Result.Panic(e)     => Console.printLine(Style.red(String.valueOf(e.getMessage)))
                }
            }
      case "add" :: name :: rest =>
        parseAddFlags(rest) match
          case Left(err) => Console.printLine(Style.red(err))
          case Right(spec) =>
            if spec.command.isEmpty && spec.url.isEmpty then
              Console.printLine(Style.red("mcp add: provide --command <cmd> (stdio) or --url <url> (http)"))
            else
              Fs.readString(paths.configYaml).map { existing =>
                val updated = apollo.mcp.McpConfigEdit.addServer(existing, name, spec)
                Fs.writeStringAtomic(paths.configYaml, updated).andThen(
                  Console.printLine(s"added MCP server '$name' to ${paths.configYaml}\n" +
                    s"verify it with: apollo mcp test $name"))
              }
      case ("remove" | "rm") :: name :: _ =>
        Fs.readString(paths.configYaml).map { existing =>
          if !apollo.mcp.McpConfigEdit.hasServer(existing, name) then
            Console.printLine(s"no MCP server named '$name' in ${paths.configYaml}")
          else
            Fs.writeStringAtomic(paths.configYaml, apollo.mcp.McpConfigEdit.removeServer(existing, name))
              .andThen(new apollo.mcp.McpOAuthStore(paths).remove(name))
              .andThen(Console.printLine(s"removed MCP server '$name'"))
        }
      case "logout" :: name :: _ =>
        new apollo.mcp.McpOAuthStore(paths).remove(name)
          .andThen(Console.printLine(s"cleared cached OAuth state for '$name'"))
      case "list" :: _ | "ls" :: _ | Nil =>
        Kyo.foreachDiscard(warnings)(w => Console.printLineErr(Style.red(s"warning: $w"))).andThen {
          if servers.isEmpty then
            Console.printLine("no MCP servers configured (add an mcp_servers: block to config.yaml)")
          else
            val rows = servers.map { s =>
              val transport = if s.isStdio then "stdio" else "http"
              val target    = s.command.orElse(s.url).getOrElse("?")
              val trust     = if s.untrusted then "  [untrusted]" else ""
              val auth      = if s.usesOAuth then "  [oauth]" else ""
              f"  ${s.name}%-20s $transport%-6s $target$trust$auth"
            }
            Console.printLine(s"mcp_servers (${servers.length}):\n${rows.mkString("\n")}\n\n" +
              "probe one with: apollo mcp test <name>; authorize oauth servers with: apollo mcp login <name>")
        }
      case other :: _ =>
        Console.printLine("mcp subcommands: list | test <name> | add <name> [--command C --arg A | " +
          s"--url U] [--env K=V] [--header K=V] [--transport sse] [--auth oauth] | remove <name> | " +
          s"login <name> | reauth <name> | logout <name> (got: $other)")
  end mcp

  /** Parses `apollo mcp add` flags into a server spec. */
  private[cli] def parseAddFlags(args: List[String]): Either[String, apollo.mcp.McpConfigEdit.ServerSpec] =
    def kv(s: String): Either[String, (String, String)] =
      s.split("=", 2) match
        case Array(k, v) if k.nonEmpty => Right(k -> v)
        case _                         => Left(s"expected KEY=VALUE, got '$s'")
    @annotation.tailrec
    def loop(rem: List[String], acc: apollo.mcp.McpConfigEdit.ServerSpec): Either[String, apollo.mcp.McpConfigEdit.ServerSpec] =
      rem match
        case Nil => Right(acc)
        case "--command" :: v :: t => loop(t, acc.copy(command = kyo.Present(v)))
        case "--arg" :: v :: t     => loop(t, acc.copy(args = acc.args :+ v))
        case "--url" :: v :: t     => loop(t, acc.copy(url = kyo.Present(v)))
        case "--transport" :: v :: t => loop(t, acc.copy(transport = kyo.Present(v)))
        case "--auth" :: v :: t    => loop(t, acc.copy(auth = kyo.Present(v)))
        case "--env" :: v :: t     => kv(v) match
            case Right(p)  => loop(t, acc.copy(env = acc.env :+ p))
            case Left(err) => Left(err)
        case "--header" :: v :: t  => kv(v) match
            case Right(p)  => loop(t, acc.copy(headers = acc.headers :+ p))
            case Left(err) => Left(err)
        case flag :: _ => Left(s"mcp add: unknown or incomplete flag '$flag'")
    loop(args, apollo.mcp.McpConfigEdit.ServerSpec())

  def memory(paths: ApolloPaths): Unit < (Sync & Async) =
    for
      mem  <- Fs.readString(paths.memoryMd)
      user <- Fs.readString(paths.userMd)
      _    <- Console.printLine(
                s"""=== MEMORY.md (${paths.memoryMd}) ===
                   |${mem.getOrElse("(empty)")}
                   |
                   |=== USER.md (${paths.userMd}) ===
                   |${user.getOrElse("(empty)")}""".stripMargin
              )
    yield ()

  /** `apollo auth <copilot|qwen> [login|status|logout]` — provider OAuth. */
  def auth(args: CliArgs, config: ApolloConfig, paths: ApolloPaths): Unit < (Sync & Async) =
    args.commandArgs match
      case "copilot" :: rest =>
        rest.headOption.getOrElse("login") match
          case "logout" => CopilotAuth.logout(paths).andThen(Console.printLine("copilot: logged out"))
          case "status" =>
            CopilotAuth.loadGithubToken(paths).map {
              case Present(_) => Console.printLine("copilot: a GitHub token is stored (chat with -m copilot:<model>)")
              case Absent     => Console.printLine("copilot: not logged in (run `apollo auth copilot login`)")
            }
          case _ => copilotLogin(paths)
      case "qwen" :: rest =>
        rest.headOption.getOrElse("login") match
          case "logout" => QwenAuth.logout(paths).andThen(Console.printLine("qwen: logged out"))
          case "status" =>
            QwenAuth.load(paths).map {
              case Present(_) => Console.printLine("qwen: an OAuth token is stored (chat with -m qwen:<model>)")
              case Absent     => Console.printLine("qwen: not logged in (run `apollo auth qwen login`)")
            }
          case _ => qwenLogin(paths)
      case _ =>
        Console.printLine("usage: apollo auth <copilot|qwen> [login|status|logout]")

  private def copilotLogin(paths: ApolloPaths): Unit < (Sync & Async) =
    CopilotAuth.deviceStart().map {
      case Result.Failure(err) => Console.printLine(Style.red(s"copilot login failed: $err"))
      case Result.Success(dc) =>
        Console.printLine(
          s"""To authorize apollo for GitHub Copilot:
             |  1. open ${dc.verificationUri}
             |  2. enter the code:  ${Style.bold(dc.userCode)}
             |waiting…""".stripMargin
        ).andThen(copilotPoll(paths, dc, dc.interval, dc.expiresIn))
    }

  private def copilotPoll(paths: ApolloPaths, dc: CopilotAuth.DeviceCode, interval: Int, remaining: Int): Unit < (Sync & Async) =
    if remaining <= 0 then Console.printLine(Style.red("copilot login timed out; try again"))
    else
      Async.sleep(interval.seconds).andThen(CopilotAuth.pollOnce(dc.deviceCode)).map {
        case CopilotAuth.Poll.Pending      => copilotPoll(paths, dc, interval, remaining - interval)
        case CopilotAuth.Poll.SlowDown     => copilotPoll(paths, dc, interval + 5, remaining - interval)
        case CopilotAuth.Poll.Error(msg)   => Console.printLine(Style.red(s"copilot login failed: $msg"))
        case CopilotAuth.Poll.Success(tok) =>
          CopilotAuth.saveGithubToken(paths, tok).andThen(
            Console.printLine(Style.green("copilot: logged in — use  -m copilot:<model>  (e.g. copilot:gpt-4o)")))
      }

  /** `apollo lsp [list]` — the language-server registry + PATH availability. */
  def lsp(args: CliArgs, config: ApolloConfig, paths: ApolloPaths): Unit < (Sync & Async) =
    val path = Option(java.lang.System.getenv("PATH")).getOrElse("")
    def onPath(bin: String): Boolean =
      bin.contains("/") && java.nio.file.Files.isExecutable(java.nio.file.Paths.get(bin)) ||
        path.split(java.io.File.pathSeparator).iterator.filter(_.nonEmpty)
          .exists(d => java.nio.file.Files.isExecutable(java.nio.file.Paths.get(d, bin)))
    val rows = apollo.lsp.Lsp.defaultServers.map { (ext, s) =>
      val bin = config.lspServerCommand(ext).map(_.head).getOrElse(s.argv.head)
      s"  .$ext  ->  $bin  ${if onPath(bin) then "(found)" else "(not on PATH)"}"
    }
    Console.printLine(s"lsp: ${if config.lspEnabled then "enabled" else "disabled"}\nservers:\n" + rows.mkString("\n"))

  /** `apollo monitoring status` — the observability configuration (logs, traces,
    * metrics) and OTLP export state. */
  def monitoring(args: CliArgs, config: ApolloConfig, paths: ApolloPaths): Unit < (Sync & Async) =
    def onoff(b: Boolean): String = if b then "enabled" else "disabled"
    Console.printLine(List(
      s"endpoint:      ${if config.otlpEndpoint.isEmpty then "(unset)" else config.otlpEndpoint}",
      s"service.name:  ${config.otlpServiceName}",
      "pillars (OTLP/HTTP JSON, content-free):",
      s"  traces:      ${onoff(apollo.obs.Monitor.tracesEnabled(config))}  → /v1/traces  (agent.turn · llm.call · tool.*)",
      s"  metrics:     ${onoff(apollo.obs.Monitor.metricsEnabled(config))}  → /v1/metrics (counters + latency histograms)",
      s"  logs:        ${onoff(apollo.obs.Monitor.logsEnabled(config))}  → /v1/logs    (structured turn events)",
      s"log level:     ${config.obsLogLevel}  (monitoring.log.level / APOLLO_LOG_LEVEL)",
      s"trace console: ${onoff(config.obsTraceConsole)}  (monitoring.trace.console / APOLLO_TRACE)"
    ).mkString("\n"))

  /** `apollo secrets [status|resolve]` — configured secret sources + a dry-run
    * resolution (values masked). */
  def secrets(args: CliArgs, config: ApolloConfig, paths: ApolloPaths): Unit < (Sync & Async) =
    args.commandArgs.headOption.getOrElse("status") match
      case "resolve" | "test" =>
        if config.secretsSources.isEmpty then Console.printLine("secrets: no sources configured (set secrets.sources)")
        else
          Console.printLine(s"secrets: resolving ${config.secretsSources.mkString(", ")}…").andThen {
            Secrets.resolveAll(config).map { additions =>
              if additions.isEmpty then Console.printLine("secrets: resolved nothing (already set, or sources unavailable)")
              else Console.printLine(additions.keys.toList.sorted
                .map(k => s"  $k = ${mask(additions(k))}").mkString("resolved:\n", "\n", ""))
            }
          }
      case _ =>
        val lines = List(
          s"sources: ${if config.secretsSources.isEmpty then "(none)" else config.secretsSources.mkString(", ")}",
          s"onepassword refs: ${config.secretsOnePasswordEnv.map(_._1).sorted.mkString(", ")}",
          s"command vars: ${config.secretsCommandEnv.map(_._1).sorted.mkString(", ")}",
          s"bitwarden project: ${config.secretsBitwardenProject.getOrElse("(none)")}")
        Console.printLine(lines.mkString("\n"))

  private def mask(v: String): String =
    if v.length <= 4 then "****" else v.take(2) + "…" + v.takeRight(2) + s" (${v.length} chars)"

  private def qwenLogin(paths: ApolloPaths): Unit < (Sync & Async) =
    QwenAuth.deviceStart().map {
      case Result.Failure(err) => Console.printLine(Style.red(s"qwen login failed: $err"))
      case Result.Success(dc) =>
        val where = dc.verificationUriComplete.getOrElse(dc.verificationUri)
        Console.printLine(
          s"""To authorize apollo for Qwen:
             |  1. open ${where}
             |  2. confirm the code:  ${Style.bold(dc.userCode)}
             |waiting…""".stripMargin
        ).andThen(qwenPoll(paths, dc, dc.interval, dc.expiresIn))
    }

  private def qwenPoll(paths: ApolloPaths, dc: QwenAuth.DeviceCode, interval: Int, remaining: Int): Unit < (Sync & Async) =
    if remaining <= 0 then Console.printLine(Style.red("qwen login timed out; try again"))
    else
      Async.sleep(interval.seconds).andThen(QwenAuth.pollOnce(dc.deviceCode, dc.verifier)).map {
        case QwenAuth.Poll.Pending      => qwenPoll(paths, dc, interval, remaining - interval)
        case QwenAuth.Poll.SlowDown     => qwenPoll(paths, dc, interval + 5, remaining - interval)
        case QwenAuth.Poll.Error(msg)   => Console.printLine(Style.red(s"qwen login failed: $msg"))
        case QwenAuth.Poll.Success(tok) =>
          QwenAuth.save(paths, tok).andThen(
            Console.printLine(Style.green("qwen: logged in — use  -m qwen:<model>  (e.g. qwen:qwen3.5-coder)")))
      }
end Commands

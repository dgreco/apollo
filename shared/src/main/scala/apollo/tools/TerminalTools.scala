package apollo.tools

import apollo.util.Jx.*
import kyo.*
import kyo.Structure.Value

/** Shell execution tools (`terminal` toolset): terminal + process_manage,
  * local backend. Commands run through `sh -c` with merged stderr, a
  * deadline, and approval gating; background processes are tracked in an
  * in-memory registry and inspected via process_manage.
  */
object TerminalTools:

  val entries: List[ToolEntry] = List(
    ToolEntry(
      name = "terminal",
      toolset = "terminal",
      description =
        "Execute a shell command. Use read_file/search_files/patch/write_file for file inspection and " +
          "editing instead of cat/grep/sed/echo. Set background=true for long-running processes and " +
          "manage them with process_manage.",
      parametersJson = """{"type":"object","properties":{
        "command":{"type":"string","description":"The shell command to execute"},
        "background":{"type":"boolean","description":"Run detached; returns a session_id","default":false},
        "timeout":{"type":"integer","description":"Seconds before the command is killed","default":180},
        "workdir":{"type":"string","description":"Working directory for the command"}
      },"required":["command"]}""".replaceAll("\n\\s*", ""),
      emoji = "💻",
      maxResultChars = 50000,
      handler = terminal
    ),
    ToolEntry(
      name = "process_manage",
      toolset = "terminal",
      description = "Manage background processes started by terminal(background=true).",
      parametersJson = """{"type":"object","properties":{
        "action":{"type":"string","enum":["list","poll","log","wait","kill"],"description":"Operation"},
        "session_id":{"type":"string","description":"Target background process"},
        "timeout":{"type":"integer","description":"Seconds to wait (action=wait)","minimum":1},
        "offset":{"type":"integer","description":"Log offset (action=log)"},
        "limit":{"type":"integer","description":"Log line limit (action=log)"}
      },"required":["action"]}""".replaceAll("\n\\s*", ""),
      emoji = "⚙️",
      maxResultChars = 50000,
      handler = processManage
    ),
    ToolEntry(
      name = "execute_code",
      toolset = "code_execution",
      description =
        "Run a short code snippet in a language (python, node/javascript, ruby, bash) and return its " +
          "output. For anything file-based or long-running, use the terminal tool instead.",
      parametersJson = """{"type":"object","properties":{
        "language":{"type":"string","description":"python | node | javascript | ruby | bash | sh"},
        "code":{"type":"string","description":"The snippet to run"},
        "timeout":{"type":"integer","description":"Seconds before it is killed","default":180},
        "workdir":{"type":"string","description":"Working directory"}
      },"required":["language","code"]}""".replaceAll("\n\\s*", ""),
      emoji = "🐍",
      maxResultChars = 50000,
      handler = executeCode
    )
  )

  /** Maps a language + snippet to a shell command string, or None if the
    * language is unsupported. Kept pure for testing. */
  private[tools] def codeCommand(language: String, code: String): Option[String] =
    val interp = language.trim.toLowerCase match
      case "python" | "python3" | "py" => Some("python3 -c")
      case "node" | "javascript" | "js" => Some("node -e")
      case "ruby" | "rb"                => Some("ruby -e")
      case "bash"                       => Some("bash -c")
      case "sh" | "shell"               => Some("sh -c")
      case _                            => None
    interp.map(prefix => s"$prefix ${shellQuote(code)}")

  private def executeCode(args: Value, ctx: ToolContext): ToolOutcome < (Sync & Async) =
    ((args / "language").asStr, (args / "code").asStr) match
      case (Absent, _) => ToolOutcome.Error("missing required parameter: language")
      case (_, Absent) => ToolOutcome.Error("missing required parameter: code")
      case (Present(language), Present(code)) =>
        codeCommand(language, code) match
          case None => ToolOutcome.Error(s"unsupported language: $language (python/node/javascript/ruby/bash/sh)")
          case Some(command) =>
            val timeout = (args / "timeout").asLong.map(_.toInt).getOrElse(ctx.config.terminalTimeoutSeconds)
            val workdir = (args / "workdir").asStr.map(w => ctx.cwd.resolve(w)).getOrElse(ctx.cwd)
            ctx.approvals.check(command, ctx.ui).map {
              case Result.Failure(reason) => ToolOutcome.Error(reason)
              case _ =>
                backendCommand(command, workdir, ctx.config) match
                  case Left(err)  => ToolOutcome.Error(err)
                  case Right(cmd) => runForeground(cmd, timeout)
            }

  // --- background process registry ---------------------------------------

  private final class Bg(
      val id: String,
      val command: String,
      val process: kyo.Process,
      val output: scala.collection.mutable.ArrayBuffer[Byte],
      @volatile var exitCode: Maybe[Int]
  ):
    def text: String = output.synchronized(new String(output.toArray, "UTF-8"))

  private val registry = new java.util.concurrent.ConcurrentHashMap[String, Bg]()
  private val counter  = new java.util.concurrent.atomic.AtomicInteger(0)

  // --- terminal -----------------------------------------------------------

  private def terminal(args: Value, ctx: ToolContext): ToolOutcome < (Sync & Async) =
    (args / "command").asStr match
      case Absent => ToolOutcome.Error("missing required parameter: command")
      case Present(command) =>
        val background = (args / "background").asBool.getOrElse(false)
        val timeout    = (args / "timeout").asLong.map(_.toInt).getOrElse(ctx.config.terminalTimeoutSeconds)
        val workdir    = (args / "workdir").asStr.map(w => ctx.cwd.resolve(w)).getOrElse(ctx.cwd)
        ctx.approvals.check(command, ctx.ui).map {
          case Result.Failure(reason) => ToolOutcome.Error(reason)
          case _ =>
            backendCommand(command, workdir, ctx.config) match
              case Left(err)  => ToolOutcome.Error(err)
              case Right(cmd) =>
                if background then spawnBackground(cmd, command) else runForeground(cmd, timeout)
        }

  /** Builds the OS command for the configured terminal backend. `local` runs
    * `sh -c` on the host in `workdir`; `docker` runs `docker exec` inside the
    * configured running container. Both keep merged stderr so the existing
    * output-collection and timeout machinery is backend-agnostic.
    */
  private[tools] def backendCommand(
      cmd: String, workdir: java.nio.file.Path, config: apollo.config.ApolloConfig
  ): Either[String, Command] =
    config.terminalBackend match
      case "local" =>
        Right(Command("sh", "-c", cmd).cwd(Path(workdir.toString)).redirectErrorStream(true))
      case "docker" =>
        config.terminalDockerContainer match
          case Absent =>
            Left("terminal.backend is 'docker' but terminal.docker.container is not set " +
              "(name a running container, or set TERMINAL_DOCKER_CONTAINER).")
          case Present(container) =>
            val wArgs = config.terminalDockerWorkdir.map(w => List("-w", w)).getOrElse(Nil)
            val eArgs = config.terminalDockerEnv.toList.sortBy(_._1).flatMap((k, v) => List("-e", s"$k=$v"))
            val argv  = List("docker", "exec") ++ config.terminalDockerExtraArgs ++ wArgs ++ eArgs ++
              List(container, "sh", "-c", cmd)
            Right(Command(argv*).redirectErrorStream(true))
      case "ssh" =>
        config.terminalSshHost match
          case Absent =>
            Left("terminal.backend is 'ssh' but terminal.ssh.host is not set " +
              "(set the host, or TERMINAL_SSH_HOST).")
          case Present(host) =>
            val target  = config.terminalSshUser.map(u => s"$u@$host").getOrElse(host)
            val portArg = config.terminalSshPort.map(p => List("-p", p.toString)).getOrElse(Nil)
            val keyArg  = config.terminalSshKeyPath.map(k => List("-i", k)).getOrElse(Nil)
            // `cd <workdir> &&` inside the remote shell (the remote path, not a local one).
            val remote  = config.terminalSshWorkdir match
              case Present(w) => s"cd ${shellQuote(w)} && $cmd"
              case Absent     => cmd
            // -T: no PTY (we capture output); BatchMode: never prompt for a password.
            val argv = List("ssh", "-T", "-o", "BatchMode=yes") ++ config.terminalSshExtraArgs ++
              portArg ++ keyArg ++ List(target, "sh", "-c", remote)
            Right(Command(argv*).redirectErrorStream(true))
      case "singularity" | "apptainer" =>
        config.terminalSingularityImage match
          case Absent =>
            Left("terminal.backend is 'singularity' but terminal.singularity.image is not set " +
              "(name a SIF/image, or set TERMINAL_SINGULARITY_IMAGE).")
          case Present(image) =>
            Right(Command(singularityArgv(
              config.terminalSingularityBinary, config.terminalSingularityExtraArgs, image, cmd)*)
              .redirectErrorStream(true))
      case "exec" | "custom" =>
        val prefix = config.terminalExecArgv
        if prefix.isEmpty then
          Left("terminal.backend is 'exec' but terminal.exec.argv is empty (set the sandbox CLI " +
            "prefix, e.g. [\"podman\",\"exec\",\"box\"] or [\"kubectl\",\"exec\",\"pod\",\"--\"]).")
        else
          Right(Command(execArgv(prefix, config.terminalExecRaw, cmd)*).redirectErrorStream(true))
      case other =>
        Left(s"unsupported terminal.backend '$other' (this build implements 'local', 'docker', " +
          "'ssh', 'singularity'/'apptainer', and 'exec'/'custom')")

  /** `singularity exec [extra] <image> sh -c '<cmd>'` — pure argv builder. */
  private[tools] def singularityArgv(
      binary: String, extra: List[String], image: String, cmd: String
  ): List[String] =
    List(binary, "exec") ++ extra ++ List(image, "sh", "-c", cmd)

  /** Generic exec backend argv: the configured prefix, then either the command
    * as a single verbatim arg (`raw`) or wrapped in `sh -c '<cmd>'`. */
  private[tools] def execArgv(prefix: List[String], raw: Boolean, cmd: String): List[String] =
    if raw then prefix :+ cmd else prefix ++ List("sh", "-c", cmd)

  /** POSIX single-quote a value for safe embedding in a remote shell string. */
  private def shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

  private def runForeground(
      osCommand: Command,
      timeoutSeconds: Int
  ): ToolOutcome < (Sync & Async) =
    Abort.run[CommandException] {
      Scope.run {
        osCommand.spawn.map { proc =>
          for
            reader <- Fiber.init(collectOutput(proc))
            exit   <- proc.waitFor(timeoutSeconds.seconds)
            output <- exit match
              case Present(_) => reader.get
              case Absent =>
                // Deadline hit: kill, then collect what was produced.
                proc.destroyForcibly.andThen(reader.get)
          yield exit match
            case Present(code) =>
              val status = if code.isSuccess then "" else s"\n[exit code: ${code.toInt}]"
              ToolOutcome.Ok((if output.isEmpty then "[no output]" else output) + status)
            case Absent =>
              ToolOutcome.Error(s"command timed out after ${timeoutSeconds}s. Partial output:\n$output")
        }
      }
    }.map {
      case Result.Success(outcome) => outcome
      case Result.Failure(e)       => ToolOutcome.Error(s"failed to launch command: $e")
      case Result.Panic(e)         => ToolOutcome.Error(s"failed to launch command: ${e.getMessage}")
    }

  private def collectOutput(proc: kyo.Process): String < (Sync & Async & Scope) =
    proc.stdout
      .fold(scala.collection.mutable.ArrayBuilder.make[Byte])((acc, b) => acc.addOne(b))
      .map(acc => new String(acc.result(), "UTF-8"))

  private def spawnBackground(osCommand: Command, displayCmd: String): ToolOutcome < (Sync & Async) =
    Abort.run[CommandException] {
      osCommand.spawnUnscoped.map { proc =>
        val id = s"bg-${counter.incrementAndGet()}"
        val bg = new Bg(id, displayCmd, proc, scala.collection.mutable.ArrayBuffer.empty[Byte], Absent)
        registry.put(id, bg)
        // Reader fiber accumulates output and records the exit code.
        Fiber.initUnscoped {
          Scope.run {
            proc.stdout.fold(()) { (_, b) =>
              bg.output.synchronized { bg.output.append(b) }
              ()
            }
          }.andThen(proc.waitFor.map(code => bg.exitCode = Present(code.toInt)))
        }.map(_ => ToolOutcome.Ok(s"""{"session_id":"$id","status":"running"}"""))
      }
    }.map {
      case Result.Success(outcome) => outcome
      case Result.Failure(e)       => ToolOutcome.Error(s"failed to launch command: $e")
      case Result.Panic(e)         => ToolOutcome.Error(s"failed to launch command: ${e.getMessage}")
    }

  // --- process_manage -----------------------------------------------------

  private def processManage(args: Value, ctx: ToolContext): ToolOutcome < (Sync & Async) =
    val action = (args / "action").asStr.getOrElse("")
    val sid    = (args / "session_id").asStr
    action match
      case "list" =>
        import scala.jdk.CollectionConverters.*
        val rows = registry.values.asScala.toList.sortBy(_.id).map { bg =>
          val status = bg.exitCode.map(c => s"exited($c)").getOrElse("running")
          s"${bg.id}  $status  ${bg.command.take(80)}"
        }
        ToolOutcome.Ok(if rows.isEmpty then "no background processes" else rows.mkString("\n"))
      case "poll" =>
        withBg(sid)(bg =>
          ToolOutcome.Ok(bg.exitCode.map(c => s"""{"status":"exited","exit_code":$c}""")
            .getOrElse("""{"status":"running"}"""))
        )
      case "log" =>
        val offset = (args / "offset").asLong.map(_.toInt).getOrElse(0)
        val limit  = (args / "limit").asLong.map(_.toInt).getOrElse(200)
        withBg(sid) { bg =>
          val lines = bg.text.split("\n", -1)
          ToolOutcome.Ok(lines.slice(offset, offset + limit).mkString("\n"))
        }
      case "wait" =>
        val timeout = (args / "timeout").asLong.map(_.toInt).getOrElse(60)
        sid.flatMap(id => Maybe.fromOption(Option(registry.get(id)))) match
          case Absent => ToolOutcome.Error("unknown session_id")
          case Present(bg) =>
            bg.process.waitFor(timeout.seconds).map {
              case Present(code) =>
                bg.exitCode = Present(code.toInt)
                ToolOutcome.Ok(s"""{"status":"exited","exit_code":${code.toInt}}""")
              case Absent => ToolOutcome.Ok("""{"status":"running","note":"timeout elapsed"}""")
            }
      case "kill" =>
        sid.flatMap(id => Maybe.fromOption(Option(registry.get(id)))) match
          case Absent => ToolOutcome.Error("unknown session_id")
          case Present(bg) =>
            bg.process.destroyForcibly.map(_ => ToolOutcome.Ok(s"""{"status":"killed"}"""))
      case other => ToolOutcome.Error(s"unknown action: $other")
  end processManage

  private def withBg(sid: Maybe[String])(f: Bg => ToolOutcome): ToolOutcome =
    sid.flatMap(id => Maybe.fromOption(Option(registry.get(id)))) match
      case Present(bg) => f(bg)
      case Absent      => ToolOutcome.Error("unknown session_id")
end TerminalTools

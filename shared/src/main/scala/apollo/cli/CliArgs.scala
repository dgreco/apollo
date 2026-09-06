package apollo.cli

import kyo.*
import scala.annotation.tailrec

/** Parsed command line, mirroring the upstream CLI surface: global flags plus
  * a subcommand. Unknown flags are collected (not fatal) so invocations
  * written for the Python CLI degrade gracefully.
  */
final case class CliArgs(
    command: Maybe[String] = Absent,
    commandArgs: List[String] = Nil,
    oneshot: Maybe[String] = Absent,         // -z / --oneshot PROMPT
    query: Maybe[String] = Absent,           // -q / --query (chat)
    model: Maybe[String] = Absent,           // -m / --model
    provider: Maybe[String] = Absent,        // --provider
    reasoning: Maybe[String] = Absent,       // --reasoning
    toolsets: Maybe[List[String]] = Absent,  // -t / --toolsets
    skills: List[String] = Nil,              // -s / --skills (repeatable)
    resume: Maybe[String] = Absent,          // -r / --resume
    continueSession: Maybe[String] = Absent, // -c / --continue [NAME]; "" = most recent
    yolo: Boolean = false,
    verbose: Boolean = false,
    quiet: Boolean = false,
    ignoreUserConfig: Boolean = false,
    version: Boolean = false,
    help: Boolean = false,
    profile: Maybe[String] = Absent,         // -p / --profile (selects the APOLLO_HOME profile)
    unknown: List[String] = Nil
)

object CliArgs:

  val subcommands: Set[String] = Set(
    "chat", "model", "config", "sessions", "skills", "cron", "gateway", "setup",
    "status", "tools", "help", "version", "doctor", "memory", "logs", "mcp",
    "auth", "secrets", "monitoring", "lsp"
  )

  def parse(argv: List[String]): CliArgs =
    @tailrec
    def go(rest: List[String], acc: CliArgs): CliArgs =
      rest match
        case Nil => acc
        case arg :: tail =>
          inline def withValue(f: (CliArgs, String) => CliArgs): (List[String], CliArgs) =
            arg.indexOf('=') match
              case i if i > 0 && arg.startsWith("--") => (tail, f(acc, arg.drop(i + 1)))
              case _ =>
                tail match
                  case v :: rest2 => (rest2, f(acc, v))
                  case Nil        => (Nil, acc)

          arg match
            case "-z" | "--oneshot" | s"--oneshot=$_" =>
              val (r, a) = withValue((c, v) => c.copy(oneshot = Present(v))); go(r, a)
            case "-q" | "--query" | s"--query=$_" =>
              val (r, a) = withValue((c, v) => c.copy(query = Present(v))); go(r, a)
            case "-m" | "--model" | s"--model=$_" =>
              val (r, a) = withValue((c, v) => c.copy(model = Present(v))); go(r, a)
            case "--provider" | s"--provider=$_" =>
              val (r, a) = withValue((c, v) => c.copy(provider = Present(v))); go(r, a)
            case "--reasoning" | s"--reasoning=$_" =>
              val (r, a) = withValue((c, v) => c.copy(reasoning = Present(v))); go(r, a)
            case "-t" | "--toolsets" | s"--toolsets=$_" =>
              val (r, a) = withValue((c, v) =>
                c.copy(toolsets = Present(v.split(",").map(_.trim).filter(_.nonEmpty).toList)))
              go(r, a)
            case "-s" | "--skills" | s"--skills=$_" =>
              val (r, a) = withValue((c, v) => c.copy(skills = c.skills ++ v.split(",").toList)); go(r, a)
            case "-r" | "--resume" | s"--resume=$_" =>
              val (r, a) = withValue((c, v) => c.copy(resume = Present(v))); go(r, a)
            case "-p" | "--profile" | s"--profile=$_" =>
              val (r, a) = withValue((c, v) => c.copy(profile = Present(v))); go(r, a)
            case "-c" | "--continue" =>
              tail match
                case v :: rest2 if !v.startsWith("-") && !subcommands.contains(v) =>
                  go(rest2, acc.copy(continueSession = Present(v)))
                case _ => go(tail, acc.copy(continueSession = Present("")))
            case "--yolo"               => go(tail, acc.copy(yolo = true))
            case "-v" | "--verbose"     => go(tail, acc.copy(verbose = true))
            case "-Q" | "--quiet"       => go(tail, acc.copy(quiet = true))
            case "--ignore-user-config" => go(tail, acc.copy(ignoreUserConfig = true))
            case "-V" | "--version"     => go(tail, acc.copy(version = true))
            case "-h" | "--help"        => go(tail, acc.copy(help = true))
            case "--tui" | "--cli" | "--safe-mode" | "--ignore-rules" | "--accept-hooks" |
                 "--pass-session-id" | "--no-restore-cwd" | "-w" | "--worktree" =>
              go(tail, acc) // accepted for compatibility; no-ops in this build
            case cmd if !cmd.startsWith("-") && subcommands.contains(cmd) && acc.command.isEmpty =>
              acc.copy(command = Present(cmd), commandArgs = tail)
            case other =>
              go(tail, acc.copy(unknown = acc.unknown :+ other))
    go(argv, CliArgs())
  end parse
end CliArgs

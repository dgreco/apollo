package apollo.gateway

import apollo.agent.{Agent, SystemPrompt, TurnCallbacks}
import apollo.config.{ApolloConfig, ApolloPaths}
import apollo.core.Message
import apollo.cron.{CronJob, CronStore}
import apollo.http.{HttpError, Transport}
import apollo.provider.*
import apollo.session.{SessionMeta, SessionStore}
import apollo.skills.SkillStore
import apollo.tools.*
import java.time.Instant
import kyo.*

/** The cron tick loop: every 60 seconds, due jobs are advanced FIRST (their
  * `next_run_at` re-armed before any execution — at-most-once semantics,
  * like the upstream harness) and then executed on a fresh agent session with no
  * conversation history. Results are delivered to stdout (`local`) or, when
  * `deliver` is `telegram:<chat_id>` and a bot token is configured, via the
  * Telegram Bot API.
  */
object CronScheduler:

  private val tickSeconds = 60

  def runLoop(config: ApolloConfig, paths: ApolloPaths): Unit < (Sync & Async) =
    tick(config, paths).andThen(Async.sleep(tickSeconds.seconds)).andThen(runLoop(config, paths))

  def tick(config: ApolloConfig, paths: ApolloPaths): Unit < (Sync & Async) =
    val store = new CronStore(paths)
    for
      now  <- Sync.defer(Instant.now())
      jobs <- store.load
      due   = jobs.filter(j =>
                j.enabled && j.state == "scheduled" &&
                  j.nextRunAt.exists(_ <= now.getEpochSecond.toDouble))
      // `trigger`ed jobs run off-tick. Their schedule is left alone, so a
      // manual run never cancels the next scheduled one (upstream #106306) —
      // and a paused job can still be run by hand.
      triggered = jobs.filter(j => j.runRequestedAt.nonEmpty && !due.exists(_.id == j.id))
      toRun     = due ++ triggered
      _    <- if toRun.isEmpty then Sync.defer(())
              else
                // Advance schedules BEFORE executing: at-most-once.
                val advanced = jobs.map { j =>
                  val cleared = if j.runRequestedAt.nonEmpty then j.copy(runRequestedAt = Absent) else j
                  if !due.exists(_.id == j.id) then cleared
                  else
                    // NB: fully qualified — `import kyo.*` shadows our Schedule.
                    apollo.cron.Schedule.advance(cleared.scheduleKind, cleared.scheduleExpr, now) match
                      case Present(nextAt) => cleared.copy(nextRunAt = Present(nextAt.getEpochSecond.toDouble))
                      case Absent          => cleared.copy(state = "completed", nextRunAt = Absent)
                }
                store.save(advanced).andThen {
                  Kyo.foreachDiscard(toRun)(job => runJob(config, paths, store, job, now))
                }
    yield ()

  private def runJob(
      config: ApolloConfig,
      paths: ApolloPaths,
      store: CronStore,
      job: CronJob,
      now: Instant
  ): Unit < (Sync & Async) =
    Console.printLine(s"cron: running ${job.id} (${job.name})").andThen {
      executeJob(config, paths, job).map { outcome =>
        val (status, output) = outcome
        val stamped = (j: CronJob) =>
          j.copy(lastRunAt = Present(now.getEpochSecond.toDouble), lastStatus = Present(status))
        store.load
          .map(jobs => store.save(jobs.map(j => if j.id == job.id then stamped(j) else j)))
          .andThen(deliver(config, job, output))
      }
    }

  private def executeJob(
      config: ApolloConfig,
      paths: ApolloPaths,
      job: CronJob
  ): (String, String) < (Sync & Async) =
    Abort.run[ResolveError](Runtime.resolve(config, RuntimeOverrides())).map {
      case Result.Failure(err) => ("error", s"provider resolution failed: ${err.message}")
      case Result.Panic(e)     => ("error", s"provider resolution failed: ${e.getMessage}")
      case Result.Success(runtime) =>
        val sessions = new SessionStore(paths)
        for
          now     <- Sync.defer(Instant.now())
          id       = sessions.newSessionId(now)
          todoRef <- AtomicRef.init(List.empty[TodoItem])
          ctx      = ToolContext(
                       config = config, paths = paths,
                       cwd = java.nio.file.Paths.get(".").toAbsolutePath.normalize,
                       platform = "cron", sessionId = id,
                       approvals = new ApprovalService(config, paths, "cron", oneShot = false, yoloFlag = false),
                       ui = UnattendedToolUi, todo = todoRef,
                       skills = new SkillStore(config, paths)
                     )
          tools    = Toolsets.forPlatform(config, "cron")
          flag     = new java.util.concurrent.atomic.AtomicBoolean(false)
          agent    = new Agent(runtime, ctx, sessions, id, config.maxTurns, flag)
          _       <- sessions.create(SessionMeta(
                       id = id, title = Present(s"cron: ${job.name.take(40)}"), platform = "cron",
                       model = runtime.model, provider = runtime.providerSlug,
                       startedAt = now.toEpochMilli / 1000.0, endedAt = Absent,
                       cwd = ctx.cwd.toString, messageCount = 0, apiCalls = 0,
                       usage = apollo.core.Usage.zero
                     ))
          system  <- SystemPrompt.build(SystemPrompt.Input(
                       config, paths, ctx.skills, ctx.cwd, "cron",
                       runtime.model, runtime.providerSlug, tools
                     ))
          result  <- agent.runTurn(Message.user(job.prompt), system, tools, TurnCallbacks())
        yield
          if result.exitReason == "text_response" then ("ok", result.finalResponse)
          else ("error", s"[${result.exitReason}] ${result.finalResponse}")
    }

  private def deliver(config: ApolloConfig, job: CronJob, output: String): Unit < (Sync & Async) =
    if output.contains("[SILENT]") then Sync.defer(())
    else
      job.deliver.getOrElse("local") match
        case s"telegram:$chatId" =>
          config.env.get("TELEGRAM_BOT_TOKEN") match
            case Present(token) =>
              val body = apollo.util.Jx.render(apollo.util.Jx.obj(
                "chat_id" -> apollo.util.Jx.str(chatId),
                "text"    -> apollo.util.Jx.str(s"⏰ ${job.name}\n\n${output.take(4000)}")
              ))
              Abort.run[HttpError](
                Transport.postJson(s"https://api.telegram.org/bot$token/sendMessage", Nil, body)
              ).unit
            case Absent =>
              Console.printLine(s"cron ${job.id}: telegram delivery configured but no TELEGRAM_BOT_TOKEN")
        case s"discord:$channelId" =>
          config.env.get("DISCORD_BOT_TOKEN") match
            case Present(token) =>
              Discord.send(token, channelId,
                s"⏰ ${job.name}\n\n${output.take(1900)}", config.env.get)
            case Absent =>
              Console.printLine(s"cron ${job.id}: discord delivery configured but no DISCORD_BOT_TOKEN")
        case s"slack:$channelId" =>
          config.env.get("SLACK_BOT_TOKEN") match
            case Present(token) =>
              Slack.sendChunked(token, channelId,
                s"⏰ ${job.name}\n\n${output.take(4000)}", config.env.get)
            case Absent =>
              Console.printLine(s"cron ${job.id}: slack delivery configured but no SLACK_BOT_TOKEN")
        case _ =>
          Console.printLine(s"cron ${job.id} result:\n${output.take(2000)}")
end CronScheduler

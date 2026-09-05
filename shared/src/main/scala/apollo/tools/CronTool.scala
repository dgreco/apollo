package apollo.tools

import apollo.cron.{CronJob, CronStore, Schedule}
import apollo.util.Jx
import apollo.util.Jx.*
import java.time.Instant
import kyo.*
import kyo.Structure.Value

/** cronjob_manage — create/list/update/pause/resume/remove scheduled jobs in
  * the upstream-compatible `<home>/cron/jobs.json` store. Execution is done
  * by the scheduler loop in the gateway/CLI host.
  */
object CronTool:

  val entries: List[ToolEntry] = List(
    ToolEntry(
      name = "cronjob_manage",
      toolset = "cronjob",
      description =
        "Manage scheduled tasks. Schedules: '30m' / 'in 30m' (one-shot), 'every 2h', 'every monday 9am', " +
          "a 5-field cron expression, or an ISO datetime.",
      parametersJson = """{"type":"object","properties":{
        "action":{"type":"string","enum":["create","list","update","pause","resume","remove"]},
        "job_id":{"type":"string"},
        "prompt":{"type":"string","description":"Full self-contained prompt for the job"},
        "schedule":{"type":"string","description":"Required for create"},
        "name":{"type":"string"},
        "deliver":{"type":"string","description":"Delivery target, e.g. 'local' or 'telegram:12345'"}
      },"required":["action"]}""".replaceAll("\n\\s*", ""),
      emoji = "⏰",
      handler = handle
    )
  )

  private def handle(args: Value, ctx: ToolContext): ToolOutcome < (Sync & Async) =
    val store  = new CronStore(ctx.paths)
    val action = (args / "action").asStr.getOrElse("")
    action match
      case "create" =>
        ((args / "prompt").asStr, (args / "schedule").asStr) match
          case (Present(prompt), Present(scheduleStr)) =>
            Sync.defer(Instant.now()).map { instant =>
              Schedule.parse(scheduleStr, instant) match
                case Result.Failure(err) => ToolOutcome.Error(err)
                case Result.Success((kind, expr, nextRun)) =>
                  val id = s"job-${java.util.UUID.randomUUID.toString.take(8)}"
                  val job = CronJob(
                    id = id,
                    name = (args / "name").asStr.getOrElse(prompt.take(40)),
                    prompt = prompt,
                    scheduleKind = kind,
                    scheduleExpr = expr,
                    scheduleDisplay = scheduleStr,
                    enabled = true,
                    state = "scheduled",
                    nextRunAt = nextRun.map(_.getEpochSecond.toDouble),
                    lastRunAt = Absent,
                    lastStatus = Absent,
                    deliver = (args / "deliver").asStr,
                    raw = Jx.obj()
                  )
                  store.upsert(job).map(_ =>
                    ToolOutcome.Ok(s"""{"job_id":"$id","next_run_at":${nextRun.map(_.toString).getOrElse("null")}}""")
                  )
                case _ => ToolOutcome.Error("schedule parse failed")
            }
          case _ => ToolOutcome.Error("create requires prompt and schedule")
      case "list" =>
        store.load.map { jobs =>
          if jobs.isEmpty then ToolOutcome.Ok("no scheduled jobs")
          else
            ToolOutcome.Ok(jobs.map { j =>
              val next = j.nextRunAt.map(t => Instant.ofEpochSecond(t.toLong).toString).getOrElse("-")
              s"${j.id}  [${j.state}${if j.enabled then "" else ", disabled"}]  ${j.scheduleDisplay}  next=$next  ${j.name}"
            }.mkString("\n"))
        }
      case "update" =>
        withJob(store, args) { job =>
          val updated = job.copy(
            prompt = (args / "prompt").asStr.getOrElse(job.prompt),
            name = (args / "name").asStr.getOrElse(job.name),
            deliver = (args / "deliver").asStr.orElse(job.deliver)
          )
          (args / "schedule").asStr match
            case Present(scheduleStr) =>
              Sync.defer(Instant.now()).map { instant =>
                Schedule.parse(scheduleStr, instant) match
                  case Result.Success((kind, expr, nextRun)) =>
                    store.upsert(updated.copy(
                      scheduleKind = kind, scheduleExpr = expr, scheduleDisplay = scheduleStr,
                      nextRunAt = nextRun.map(_.getEpochSecond.toDouble)
                    )).map(_ => ToolOutcome.Ok(s"updated ${job.id}"))
                  case Result.Failure(err) => ToolOutcome.Error(err)
                  case _                   => ToolOutcome.Error("schedule parse failed")
              }
            case Absent =>
              store.upsert(updated).map(_ => ToolOutcome.Ok(s"updated ${job.id}"))
        }
      case "pause" =>
        withJob(store, args)(job =>
          store.upsert(job.copy(state = "paused", enabled = false)).map(_ => ToolOutcome.Ok(s"paused ${job.id}"))
        )
      case "resume" =>
        withJob(store, args)(job =>
          store.upsert(job.copy(state = "scheduled", enabled = true)).map(_ => ToolOutcome.Ok(s"resumed ${job.id}"))
        )
      case "remove" =>
        (args / "job_id").asStr match
          case Absent => ToolOutcome.Error("remove requires job_id")
          case Present(id) =>
            store.load.map { jobs =>
              if !jobs.exists(_.id == id) then ToolOutcome.Error(s"unknown job_id: $id")
              else store.save(jobs.filterNot(_.id == id)).map(_ => ToolOutcome.Ok(s"removed $id"))
            }
      case other => ToolOutcome.Error(s"unknown action: $other")
  end handle

  private def withJob(store: CronStore, args: Value)(
      f: CronJob => ToolOutcome < (Sync & Async)
  ): ToolOutcome < (Sync & Async) =
    (args / "job_id").asStr match
      case Absent => ToolOutcome.Error("missing job_id")
      case Present(id) =>
        store.load.map { jobs =>
          jobs.find(_.id == id) match
            case Some(job) => f(job)
            case None      => ToolOutcome.Error(s"unknown job_id: $id")
        }
end CronTool

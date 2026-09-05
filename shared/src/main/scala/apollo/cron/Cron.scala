package apollo.cron

import apollo.config.{Fs, ApolloPaths}
import apollo.util.Jx
import apollo.util.Jx.*
import java.time.{Instant, ZonedDateTime, ZoneId, Duration as JDuration}
import kyo.*
import kyo.Structure.Value

/** One scheduled job, persisted to `<home>/cron/jobs.json` in a
  * upstream-compatible record shape (unknown fields from a real the upstream harness file are
  * preserved on rewrite via the raw JSON object).
  */
final case class CronJob(
    id: String,
    name: String,
    prompt: String,
    scheduleKind: String, // "relative" | "interval" | "cron" | "iso" | "weekly"
    scheduleExpr: String,
    scheduleDisplay: String,
    enabled: Boolean,
    state: String, // scheduled | paused | completed | running
    nextRunAt: Maybe[Double],
    lastRunAt: Maybe[Double],
    lastStatus: Maybe[String],
    deliver: Maybe[String],
    raw: Value // original record; our fields overlay it on save
)

object Schedule:

  /** Parses the upstream schedule forms: `30m` / `in 30m` (one-shot relative),
    * `every 2h` (interval), `every monday 9am` (weekly), a 5-field cron
    * expression, or an ISO datetime (one-shot).
    */
  def parse(input: String, now: Instant): Result[String, (String, String, Maybe[Instant])] =
    val s = input.trim.toLowerCase
    val relative = """(?:in\s+)?(\d+)\s*(m|min|minutes?|h|hours?|d|days?)""".r
    val interval = """every\s+(\d+)\s*(m|min|minutes?|h|hours?|d|days?)""".r
    val weekly   = """every\s+(monday|tuesday|wednesday|thursday|friday|saturday|sunday)\s+(\d{1,2})(?::(\d{2}))?\s*(am|pm)?""".r
    s match
      case interval(n, unit) =>
        Result.succeed(("interval", s, Present(now.plus(toDuration(n.toLong, unit)))))
      case weekly(day, hour, minutes, ampm) =>
        Result.succeed(("weekly", s, Present(nextWeekly(now, day, hour.toInt, Option(minutes).map(_.toInt).getOrElse(0), Option(ampm)))))
      case relative(n, unit) =>
        Result.succeed(("relative", s, Present(now.plus(toDuration(n.toLong, unit)))))
      case _ if s.split("\\s+").length == 5 =>
        CronExpr.parse(s).map(expr => ("cron", s, Present(expr.next(now))))
      case _ =>
        scala.util.Try(Instant.parse(input.trim)).toOption
          .orElse(scala.util.Try(java.time.LocalDateTime.parse(input.trim)).toOption
            .map(_.atZone(ZoneId.systemDefault).toInstant)) match
          case Some(at) => Result.succeed(("iso", input.trim, Present(at)))
          case None     => Result.fail(s"unrecognized schedule: $input")
  end parse

  /** Next occurrence after a run, for recurring kinds; Absent = one-shot. */
  def advance(kind: String, expr: String, now: Instant): Maybe[Instant] =
    kind match
      case "interval" =>
        """every\s+(\d+)\s*(m|min|minutes?|h|hours?|d|days?)""".r.findFirstMatchIn(expr)
          .map(m => now.plus(toDuration(m.group(1).toLong, m.group(2)))) match
          case Some(i) => Present(i)
          case None    => Absent
      case "weekly" =>
        """every\s+(\w+)\s+(\d{1,2})(?::(\d{2}))?\s*(am|pm)?""".r.findFirstMatchIn(expr)
          .map(m => nextWeekly(now, m.group(1), m.group(2).toInt, Option(m.group(3)).map(_.toInt).getOrElse(0), Option(m.group(4)))) match
          case Some(i) => Present(i)
          case None    => Absent
      case "cron" =>
        CronExpr.parse(expr) match
          case Result.Success(e) => Present(e.next(now))
          case _                 => Absent
      case _ => Absent // relative / iso are one-shot

  private def toDuration(n: Long, unit: String): JDuration =
    unit.head match
      case 'm' => JDuration.ofMinutes(n)
      case 'h' => JDuration.ofHours(n)
      case 'd' => JDuration.ofDays(n)
      case _   => JDuration.ofMinutes(n)

  private def nextWeekly(now: Instant, day: String, hour: Int, minute: Int, ampm: Option[String]): Instant =
    val dow = List("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")
      .indexOf(day.toLowerCase) + 1
    val h = ampm match
      case Some("pm") if hour < 12 => hour + 12
      case Some("am") if hour == 12 => 0
      case _                        => hour
    val zone  = ZoneId.systemDefault
    var zdt   = ZonedDateTime.ofInstant(now, zone).withHour(h).withMinute(minute).withSecond(0).withNano(0)
    while zdt.getDayOfWeek.getValue != dow || !zdt.toInstant.isAfter(now) do zdt = zdt.plusDays(1)
    zdt.toInstant
end Schedule

/** Minimal 5-field cron expression (min hour dom mon dow) evaluator. */
final case class CronExpr(min: Set[Int], hour: Set[Int], dom: Set[Int], mon: Set[Int], dow: Set[Int]):
  def next(after: Instant): Instant =
    val zone = ZoneId.systemDefault
    var t    = ZonedDateTime.ofInstant(after, zone).plusMinutes(1).withSecond(0).withNano(0)
    var i    = 0
    while i < 366 * 24 * 60 do
      val dowVal = t.getDayOfWeek.getValue % 7 // cron: 0 = Sunday
      if mon.contains(t.getMonthValue) && dom.contains(t.getDayOfMonth) && dow.contains(dowVal)
        && hour.contains(t.getHour) && min.contains(t.getMinute)
      then return t.toInstant
      t = t.plusMinutes(1)
      i += 1
    t.toInstant

object CronExpr:
  def parse(expr: String): Result[String, CronExpr] =
    expr.trim.split("\\s+") match
      case Array(m, h, dom, mon, dow) =>
        for
          mm   <- field(m, 0, 59)
          hh   <- field(h, 0, 23)
          dd   <- field(dom, 1, 31)
          mo   <- field(mon, 1, 12)
          wd   <- field(dow, 0, 7).map(_.map(v => if v == 7 then 0 else v))
        yield CronExpr(mm, hh, dd, mo, wd)
      case _ => Result.fail(s"cron expression must have 5 fields: $expr")

  private def field(spec: String, lo: Int, hi: Int): Result[String, Set[Int]] =
    try
      val values = spec.split(",").flatMap { part =>
        part match
          case "*" => (lo to hi).toList
          case s if s.startsWith("*/") =>
            val step = s.drop(2).toInt
            (lo to hi).filter(v => (v - lo) % step == 0).toList
          case s if s.contains("-") =>
            val Array(a, b) = s.split("-", 2)
            (a.toInt to b.toInt).toList
          case s => List(s.toInt)
      }.toSet
      if values.forall(v => v >= lo && v <= hi) then Result.succeed(values)
      else Result.fail(s"cron field out of range: $spec")
    catch case _: Exception => Result.fail(s"invalid cron field: $spec")
end CronExpr

/** Load/save of the upstream `cron/jobs.json` store. */
final class CronStore(paths: ApolloPaths):

  private def file = paths.cronJobs

  def load: List[CronJob] < Sync =
    Fs.readString(file).map {
      case Absent => Nil
      case Present(text) =>
        Jx.parse(text) match
          case Result.Success(json) =>
            val jobs = (json / "jobs").asArr.orElse(json.asArr).getOrElse(Chunk.empty)
            jobs.toList.flatMap(v => parseJob(v).toList)
          case _ => Nil
    }

  private def parseJob(v: Value): Maybe[CronJob] =
    (v / "id").asStr.map { id =>
      CronJob(
        id = id,
        name = (v / "name").asStr.getOrElse(id),
        prompt = (v / "prompt").asStr.getOrElse(""),
        scheduleKind = (v / "schedule" / "kind").asStr.getOrElse("relative"),
        scheduleExpr = (v / "schedule" / "expr").asStr.getOrElse(""),
        scheduleDisplay = (v / "schedule" / "display").asStr.getOrElse(""),
        enabled = (v / "enabled").asBool.getOrElse(true),
        state = (v / "state").asStr.getOrElse("scheduled"),
        nextRunAt = (v / "next_run_at").asDouble,
        lastRunAt = (v / "last_run_at").asDouble,
        lastStatus = (v / "last_status").asStr,
        deliver = (v / "deliver").asStr,
        raw = v
      )
    }

  def save(jobs: List[CronJob]): Unit < Sync =
    val rendered = jobs.map { j =>
      j.raw.deepMerge(Jx.objOf(
        "id"       -> Present(Jx.str(j.id)),
        "name"     -> Present(Jx.str(j.name)),
        "prompt"   -> Present(Jx.str(j.prompt)),
        "schedule" -> Present(Jx.obj(
          "kind"    -> Jx.str(j.scheduleKind),
          "expr"    -> Jx.str(j.scheduleExpr),
          "display" -> Jx.str(j.scheduleDisplay)
        )),
        "enabled"     -> Present(Jx.bool(j.enabled)),
        "state"       -> Present(Jx.str(j.state)),
        "next_run_at" -> j.nextRunAt.map(Jx.num),
        "last_run_at" -> j.lastRunAt.map(Jx.num),
        "last_status" -> j.lastStatus.map(Jx.str),
        "deliver"     -> j.deliver.map(Jx.str)
      ))
    }
    Fs.writeStringAtomic(file, Jx.render(Jx.obj("jobs" -> Jx.arr(rendered))))

  def upsert(job: CronJob): Unit < Sync =
    load.map(jobs => save(jobs.filterNot(_.id == job.id) :+ job))

  def remove(id: String): Boolean < Sync =
    load.map { jobs =>
      val remaining = jobs.filterNot(_.id == id)
      if remaining.length == jobs.length then false
      else save(remaining).map(_ => true)
    }
end CronStore

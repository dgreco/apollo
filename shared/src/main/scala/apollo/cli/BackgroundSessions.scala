package apollo.cli

/** Process-global registry of background REPL sessions started with `/bg`.
  * Each job runs its own Agent on its own session in a detached fiber; the
  * registry tracks status and holds the interrupt flag so `/stop` can cancel it.
  */
object BackgroundSessions:

  enum Status:
    case Running
    case Done(exitReason: String, chars: Int)
    case Failed(msg: String)
    case Cancelled

  final case class Job(
      id: String,
      prompt: String,
      startedAt: Double,
      status: Status,
      flag: java.util.concurrent.atomic.AtomicBoolean
  )

  private val jobs = new java.util.concurrent.ConcurrentHashMap[String, Job]()

  def put(j: Job): Unit = { jobs.put(j.id, j); () }

  def finish(id: String, exitReason: String, chars: Int): Unit =
    Option(jobs.get(id)).foreach(j => jobs.put(id, j.copy(status = Status.Done(exitReason, chars))))

  def fail(id: String, msg: String): Unit =
    Option(jobs.get(id)).foreach(j => jobs.put(id, j.copy(status = Status.Failed(msg))))

  /** Signal cancellation via the job's interrupt flag (the running turn aborts). */
  def cancel(id: String): Boolean =
    Option(jobs.get(id)) match
      case Some(j) =>
        j.flag.set(true)
        jobs.put(id, j.copy(status = Status.Cancelled))
        true
      case None => false

  def all: List[Job] =
    import scala.jdk.CollectionConverters.*
    jobs.values.asScala.toList.sortBy(_.startedAt)

  /** Test hook — clears the process-global registry. */
  def reset(): Unit = jobs.clear()
end BackgroundSessions

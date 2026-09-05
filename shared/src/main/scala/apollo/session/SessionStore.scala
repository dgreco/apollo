package apollo.session

import apollo.config.{Fs, ApolloPaths}
import apollo.core.{Message, Usage}
import apollo.util.Jx
import apollo.util.Jx.*
import java.time.format.DateTimeFormatter
import java.time.{Instant, ZoneId}
import kyo.*

/** Session metadata (the subset of the upstream harness's `sessions` table this build
  * tracks).
  */
final case class SessionMeta(
    id: String,
    title: Maybe[String],
    platform: String,
    model: String,
    provider: String,
    startedAt: Double,
    endedAt: Maybe[Double],
    cwd: String,
    messageCount: Int,
    apiCalls: Int,
    usage: Usage
) derives Schema

/** Durable session storage. the upstream harness keeps sessions in SQLite (`state.db`);
  * this build uses an append-only JSONL transcript per session plus a JSON
  * index — same information, dependency-free on Scala Native. Files live
  * under `<home>/scala-state/`.
  */
final class SessionStore(paths: ApolloPaths):

  private def root        = paths.home.resolve("scala-state")
  private def sessionsDir = root.resolve("sessions")
  private def indexFile   = root.resolve("sessions-index.json")

  def newSessionId(now: Instant): String =
    val stamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
      .withZone(ZoneId.systemDefault)
      .format(now)
    val suffix = java.util.UUID.randomUUID.toString.replace("-", "").take(6)
    s"${stamp}_$suffix"

  private def transcriptFile(id: String) = sessionsDir.resolve(s"$id.jsonl")

  def create(meta: SessionMeta): Unit < Sync =
    updateIndex(metas => metas.filterNot(_.id == meta.id) :+ meta)

  def appendMessage(id: String, message: Message): Unit < Sync =
    Fs.appendString(transcriptFile(id), Json.encode(message) + "\n")

  def loadTranscript(id: String): List[Message] < Sync =
    Fs.readString(transcriptFile(id)).map {
      case Absent => Nil
      case Present(text) =>
        text.linesIterator.filter(_.nonEmpty).flatMap { line =>
          Json.decode[Message](line) match
            case Result.Success(m) => Some(m)
            case _                 => None
        }.toList
    }

  /** Replaces the transcript (used after context compression). */
  def rewriteTranscript(id: String, messages: List[Message]): Unit < Sync =
    Fs.writeStringAtomic(transcriptFile(id), messages.map(Json.encode(_)).mkString("", "\n", "\n"))

  def updateMeta(id: String)(f: SessionMeta => SessionMeta): Unit < Sync =
    updateIndex(_.map(m => if m.id == id then f(m) else m))

  def list: List[SessionMeta] < Sync =
    Fs.readString(indexFile).map {
      case Absent => Nil
      case Present(text) =>
        Json.decode[List[SessionMeta]](text) match
          case Result.Success(metas) => metas.sortBy(-_.startedAt)
          case _                     => Nil
    }

  def find(idOrTitle: String): Maybe[SessionMeta] < Sync =
    list.map { metas =>
      if idOrTitle == "latest" then Maybe.fromOption(metas.headOption)
      else
        Maybe.fromOption(
          metas.find(_.id == idOrTitle)
            .orElse(metas.find(_.title.contains(idOrTitle)))
            .orElse(metas.find(_.id.startsWith(idOrTitle)))
        )
    }

  /** Read-modify-write of the shared index, serialized per index file
    * across all `SessionStore` instances in this process. The gateway runs
    * turns for different chats on concurrent consumer fibers, so unsynchronized
    * creates/updates would both lose entries and collide on the atomic-write
    * temp file; this holds a JVM monitor for the whole cycle and gives each
    * write a unique temp name.
    */
  private def updateIndex(f: List[SessionMeta] => List[SessionMeta]): Unit < Sync =
    Sync.defer {
      SessionStore.indexLock(indexFile.toString).synchronized {
        import java.nio.file.*
        val current =
          if Files.exists(indexFile) then
            Json.decode[List[SessionMeta]](new String(Files.readAllBytes(indexFile), "UTF-8")) match
              case Result.Success(metas) => metas
              case _                     => Nil
          else Nil
        val parent = indexFile.getParent
        if parent != null then Files.createDirectories(parent)
        val tmp = indexFile.resolveSibling(s"sessions-index.json.${java.lang.System.nanoTime()}.tmp")
        Files.write(tmp, Json.encode(f(current)).getBytes("UTF-8"))
        Files.move(tmp, indexFile, StandardCopyOption.REPLACE_EXISTING)
        ()
      }
    }

  /** Backing for the session_search tool. Delegates to the platform search
    * backend: SQLite FTS5 on the JVM, the pure-Scala transcript scan on
    * Native (which keeps that platform dependency-free). Both return the same
    * `[id] title: snippet` line format.
    */
  def search(query: String, limit: Int): String < Sync =
    PlatformSearch.search(this, query, limit)

  /** The SQLite FTS5 index file (JVM backend only). */
  private[session] def searchDbFile: java.nio.file.Path = root.resolve("search.db")

  /** Pure-Scala transcript scan — the Native backend and the JVM fallback. */
  private[session] def scanSearch(query: String, limit: Int): String < Sync =
    val terms = query.toLowerCase.split("\\s+").filter(_.nonEmpty).toList
    list.map { metas =>
      Kyo.foreach(metas.take(200)) { meta =>
        loadTranscript(meta.id).map { messages =>
          val hits = messages.flatMap { m =>
            m.content.collect {
              case apollo.core.Content.Text(t)
                  if terms.nonEmpty && terms.forall(t.toLowerCase.contains) =>
                t
            }
          }
          if hits.isEmpty then Absent
          else
            val snippet = hits.head.linesIterator
              .find(l => terms.exists(l.toLowerCase.contains))
              .getOrElse(hits.head.take(200))
            Present(s"[${meta.id}] ${meta.title.getOrElse("(untitled)")}: ${snippet.take(200)}")
        }
      }.map { results =>
        val found = results.flatMap(_.toList).take(limit)
        if found.isEmpty then "no matching sessions"
        else found.mkString("\n")
      }
    }
end SessionStore

object SessionStore:
  /** One JVM monitor per index-file path, shared across `SessionStore`
    * instances so concurrent gateway sessions serialize their index writes.
    */
  private val locks = new java.util.concurrent.ConcurrentHashMap[String, AnyRef]()
  private def indexLock(path: String): AnyRef =
    locks.computeIfAbsent(path, _ => new AnyRef)
end SessionStore

// SPDX-FileCopyrightText: 2026 David Greco <greco@acm.org>
//
// SPDX-License-Identifier: Apache-2.0

package apollo.session

import apollo.config.{ApolloPaths, Fs}
import apollo.util.Jx
import apollo.util.Jx.*
import kyo.*

/** Filesystem control channel for `/handoff`: the interactive REPL writes a
  * pending-handoff record for a platform; a running `apollo gateway` (a separate
  * process, same home) consumes it when that platform's next session is created
  * and seeds the new session from the handed-off transcript. Consume-once.
  */
object HandoffStore:

  final case class Handoff(sessionId: String, createdAt: Double)

  private val platforms = Set("telegram", "discord", "slack")
  def isPlatform(p: String): Boolean = platforms.contains(p)

  private def dir(paths: ApolloPaths)            = paths.home.resolve("handoffs")
  def file(paths: ApolloPaths, platform: String) = dir(paths).resolve(s"$platform.json")

  def format(sessionId: String, createdAt: Double): String =
    Jx.render(Jx.obj("session_id" -> Jx.str(sessionId), "created_at" -> Jx.num(createdAt)))

  def parse(json: String): Maybe[Handoff] =
    Jx.parse(json) match
      case Result.Success(v) =>
        (v / "session_id").asStr match
          case Present(id) => Present(Handoff(id, (v / "created_at").asDouble.getOrElse(0.0)))
          case Absent      => Absent
      case _ => Absent

  def write(paths: ApolloPaths, platform: String, sessionId: String, now: Double): Unit < Sync =
    Fs.createDirs(dir(paths)).andThen(Fs.writeString(file(paths, platform), format(sessionId, now)))

  /** Read + delete the pending handoff for a platform (Absent if none). */
  def consume(paths: ApolloPaths, platform: String): Maybe[Handoff] < Sync =
    val f = file(paths, platform)
    Fs.readString(f).map {
      case Present(s) => Fs.delete(f).andThen(parse(s))
      case Absent     => Sync.defer(Absent: Maybe[Handoff])
    }
end HandoffStore

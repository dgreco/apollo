package apollo.skills

import apollo.config.{ApolloConfig, ApolloPaths, Fs}
import apollo.provider.{ProviderError, Transport}
import apollo.util.Jx
import apollo.util.Jx.*
import kyo.*
import kyo.Structure.Value
import java.nio.file.Path

/** Skills Hub: install skills from a git repo or a local directory, and search a
  * remote catalog. Native-safe — git/cp run as subprocesses (no tar/gzip), the
  * catalog is plain HTTP JSON. The pure helpers are unit-tested; the git clone /
  * catalog fetch are validated live. */
object SkillsHub:

  final case class CatalogEntry(name: String, description: String, source: String)

  /** A git source → a clone URL. `owner/repo` shorthand → GitHub; explicit
    * URLs (https/ssh) pass through; anything else (a local path) → None. */
  def normalizeGitUrl(source: String): Option[String] =
    val s = source.trim
    if s.contains("://") || s.startsWith("git@") then Some(s)
    else if s.matches("[\\w.-]+/[\\w.-]+") then Some(s"https://github.com/$s.git")
    else None

  def gitCloneArgs(url: String, dest: String): List[String] =
    List("git", "clone", "--depth", "1", url, dest)

  def parseCatalog(json: Value): List[CatalogEntry] =
    (json / "skills").asArr.getOrElse(Chunk.empty).toList.flatMap { e =>
      (e / "name").asStr match
        case Present(n) => List(CatalogEntry(n, (e / "description").asStr.getOrElse(""), (e / "source").asStr.getOrElse("")))
        case Absent     => Nil
    }

  def searchCatalog(entries: List[CatalogEntry], query: String): List[CatalogEntry] =
    val q = query.trim.toLowerCase
    entries.filter(e => e.name.toLowerCase.contains(q) || e.description.toLowerCase.contains(q))

  /** Directories that are skills (contain SKILL.md), scanning `root`, its
    * children, and grandchildren (covers repo-is-a-skill and repo/<name> and
    * repo/<category>/<name> layouts). */
  def findSkillDirs(root: Path): List[Path] < Sync = collectSkillDirs(root)

  private def childDirs(d: Path): List[Path] < Sync =
    Fs.list(d).map(_.filter(java.nio.file.Files.isDirectory(_)))

  /** root + children + grandchildren that contain a SKILL.md. */
  private def collectSkillDirs(root: Path): List[Path] < Sync =
    for
      l1         <- childDirs(root)
      l2         <- Kyo.foreach(l1)(childDirs).map(_.flatten)
      candidates  = root :: (l1 ::: l2)
      withSkill  <- Kyo.foreach(candidates)(d =>
                      Fs.exists(d.resolve("SKILL.md")).map(has => if has then List(d) else (Nil: List[Path]))
                    ).map(_.flatten)
    yield withSkill

  private def sh(argv: List[String]): Result[String, String] < (Sync & Async) =
    Abort.run[kyo.CommandException](Command(argv*).text).map {
      case Result.Success(out) => Result.succeed(out)
      case Result.Failure(e)   => Result.fail(e.getMessage)
      case Result.Panic(e)     => Result.fail(String.valueOf(e.getMessage))
    }

  /** Install skills from a git repo or a local directory into the skills dir.
    * Returns the installed skill names or an error message. */
  def install(paths: ApolloPaths, source: String): Result[String, List[String]] < (Sync & Async) =
    normalizeGitUrl(source) match
      case Some(url) =>
        Sync.defer(java.nio.file.Files.createTempDirectory("apollo-skillhub").toString).map { tmp =>
          sh(gitCloneArgs(url, tmp)).map { cloneRes =>
            val out: Result[String, List[String]] < (Sync & Async) = cloneRes match
              case Result.Failure(e) => Result.fail(s"git clone failed: $e")
              case Result.Success(_) => copySkillsFrom(java.nio.file.Paths.get(tmp), paths)
            out
          }
        }
      case None =>
        val src = java.nio.file.Paths.get(source)
        Fs.isDirectory(src).map { isDir =>
          val out: Result[String, List[String]] < (Sync & Async) =
            if !isDir then Result.fail(s"not a git source or local directory: $source")
            else copySkillsFrom(src, paths)
          out
        }

  private def copySkillsFrom(root: Path, paths: ApolloPaths): Result[String, List[String]] < (Sync & Async) =
    collectSkillDirs(root).map { dirs =>
      val out: Result[String, List[String]] < (Sync & Async) =
        if dirs.isEmpty then Result.fail("no skills (SKILL.md) found in the source")
        else
          Fs.createDirs(paths.skillsDir).andThen {
            Kyo.foreach(dirs) { d =>
              val dest = paths.skillsDir.resolve(d.getFileName.toString)
              sh(List("cp", "-r", d.toString, dest.toString)).map(_ => d.getFileName.toString)
            }.map(names => Result.succeed(names))
          }
      out
    }

  /** Search the configured catalog (`skills.hub_catalog_url`). */
  def search(config: ApolloConfig, query: String): Result[String, List[CatalogEntry]] < (Sync & Async) =
    config.skillsHubCatalogUrl match
      case Absent => Result.fail("no catalog configured (set skills.hub_catalog_url)")
      case Present(url) =>
        Abort.run[ProviderError](Transport.getJson(url, Nil)).map {
          case Result.Success(body) =>
            Jx.parse(body) match
              case Result.Success(j) => Result.succeed(searchCatalog(parseCatalog(j), query))
              case _                 => Result.fail("unparseable catalog")
          case Result.Failure(e) => Result.fail(s"catalog fetch failed: ${e.getMessage}")
          case Result.Panic(e)   => Result.fail(s"catalog fetch failed: ${String.valueOf(e.getMessage)}")
        }
end SkillsHub

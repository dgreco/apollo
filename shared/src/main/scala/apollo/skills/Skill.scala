package apollo.skills

import apollo.config.{Fs, ApolloConfig, ApolloPaths, Yaml}
import apollo.config.Yaml.*
import java.nio.file.Path
import kyo.*

/** One skill on disk: a directory holding `SKILL.md` (YAML frontmatter +
  * markdown body) plus optional `references/ templates/ scripts/ assets/`
  * support dirs — the agentskills.io layout the upstream harness uses.
  */
final case class Skill(
    name: String,
    description: String,
    category: String,
    dir: Path,
    createdBy: Maybe[String],
    external: Boolean
)

object Skill:
  val supportDirs: Set[String] = Set("references", "templates", "assets", "scripts")
  val excludedDirs: Set[String] = Set(
    ".git", ".github", ".hub", ".archive", ".curator_backups", ".venv", "venv",
    "node_modules", "site-packages", "__pycache__", ".tox", ".pytest_cache", ".mypy_cache"
  )

  /** Parses SKILL.md frontmatter leniently (bad YAML falls back to naive
    * `key: value` splitting; never fails).
    */
  def parseFrontmatter(content: String): (Map[String, String], String) =
    val stripped = content.stripPrefix("﻿")
    if !stripped.startsWith("---") then (Map.empty, stripped)
    else
      val rest = stripped.drop(3)
      val closeIdx = rest.indexOf("\n---")
      if closeIdx < 0 then (Map.empty, stripped)
      else
        val front = rest.take(closeIdx)
        val body  = rest.drop(closeIdx + 4).dropWhile(_ == '\n')
        val parsed = Yaml.parse(front) match
          case Result.Success(node) =>
            node.entries.getOrElse(Nil).flatMap((k, v) => v.str.map(k -> _).toList).toMap
          case _ =>
            front.linesIterator.flatMap { line =>
              line.indexOf(':') match
                case -1 => None
                case i  => Some(line.take(i).trim -> line.drop(i + 1).trim.stripPrefix("\"").stripSuffix("\""))
            }.toMap
        (parsed, body)
end Skill

/** Discovery and progressive-disclosure access over the skill roots:
  * `<home>/skills` (writable) plus read-only `skills.external_dirs`,
  * with local skills winning name collisions and `skills.disabled` minus
  * the essential set filtered out.
  */
final class SkillStore(config: ApolloConfig, paths: ApolloPaths):

  private val essential = Set.empty[String]

  def localDir: Path = paths.skillsDir

  def scan: List[Skill] < Sync =
    val disabled = config.skillsDisabled.toSet -- essential
    for
      local    <- scanRoot(localDir, external = false)
      external <- Kyo.foreach(config.skillsExternalDirs)(d => scanRoot(Fs.resolve(d), external = true))
    yield
      val locals     = local.filterNot(s => disabled.contains(s.name))
      val localNames = locals.map(_.name).toSet
      locals ++ external.flatten.filterNot(s => localNames.contains(s.name) || disabled.contains(s.name))

  private def scanRoot(root: Path, external: Boolean): List[Skill] < Sync =
    Sync.defer {
      import scala.jdk.CollectionConverters.*
      if !java.nio.file.Files.isDirectory(root) then Nil
      else
        val out = List.newBuilder[Skill]
        def walk(dir: Path, category: String, depth: Int): Unit =
          if depth <= 3 then
            val name = dir.getFileName.toString
            if !Skill.excludedDirs.contains(name) && !(depth > 0 && Skill.supportDirs.contains(name)) then
              val skillMd = dir.resolve("SKILL.md")
              if java.nio.file.Files.isRegularFile(skillMd) then
                val content       = new String(java.nio.file.Files.readAllBytes(skillMd), "UTF-8")
                val (front, _)    = Skill.parseFrontmatter(content)
                out += Skill(
                  name = front.getOrElse("name", name),
                  description = front.getOrElse("description", ""),
                  category = if category.isEmpty then "general" else category,
                  dir = dir,
                  createdBy = Maybe.fromOption(front.get("created_by")),
                  external = external
                )
              else
                val stream = java.nio.file.Files.list(dir)
                val children =
                  try stream.iterator.asScala.toList.sortBy(_.getFileName.toString)
                  finally stream.close()
                children.filter(java.nio.file.Files.isDirectory(_)).foreach { child =>
                  walk(child, if depth == 0 then "" else if category.isEmpty then name else category, depth + 1)
                }
        // Root's direct children are categories (or skills themselves).
        val stream = java.nio.file.Files.list(root)
        val top =
          try stream.iterator.asScala.toList.sortBy(_.getFileName.toString)
          finally stream.close()
        top.filter(java.nio.file.Files.isDirectory(_)).foreach { child =>
          val skillMd = child.resolve("SKILL.md")
          if java.nio.file.Files.isRegularFile(skillMd) then walk(child, "", 1)
          else
            val stream2 = java.nio.file.Files.list(child)
            val inner =
              try stream2.iterator.asScala.toList.sortBy(_.getFileName.toString)
              finally stream2.close()
            inner.filter(java.nio.file.Files.isDirectory(_)).foreach(walk(_, child.getFileName.toString, 2))
        }
        out.result()
    }
  end scanRoot

  def find(name: String): Maybe[Skill] < Sync =
    scan.map(skills => Maybe.fromOption(skills.find(s => s.name == name || s.dir.getFileName.toString == name)))

  /** The `## Skills` system-prompt index block (upstream `_render_skills_index`
    * shape), or empty when no skills exist.
    */
  def renderIndex: String < Sync =
    scan.map { skills =>
      if skills.isEmpty then ""
      else
        val byCategory = skills.groupBy(_.category).toList.sortBy(_._1)
        val listing = byCategory.map { (cat, items) =>
          s"  $cat:\n" + items.sortBy(_.name).map(s => s"    - ${s.name}: ${s.description}").mkString("\n")
        }.mkString("\n")
        s"""## Skills
           |Before replying, scan the skills below. If a skill matches or is even partially relevant to
           |your task, you MUST load it with skill_view(name) and follow its instructions. If a skill has
           |issues, fix it with skill_manage(action='patch').
           |
           |<available_skills>
           |$listing
           |</available_skills>
           |
           |Only proceed without loading a skill if genuinely none are relevant to the task.""".stripMargin
    }

  /** Reads SKILL.md (or a linked support file) for skill_view. */
  def view(name: String, filePath: Maybe[String]): Result[String, String] < Sync =
    find(name).map {
      case Absent => Result.fail(s"skill not found: $name")
      case Present(skill) =>
        filePath match
          case Present(rel) =>
            val target = skill.dir.resolve(rel).normalize
            if !target.startsWith(skill.dir) then Result.fail("file_path escapes the skill directory")
            else
              Fs.readString(target).map {
                case Present(text) => Result.succeed(text)
                case Absent        => Result.fail(s"file not found in skill: $rel")
              }
          case Absent =>
            Fs.readString(skill.dir.resolve("SKILL.md")).map {
              case Present(text) =>
                linkedFiles(skill).map { files =>
                  val listing =
                    if files.isEmpty then ""
                    else "\n\n[linked files: " + files.mkString(", ") + " — load with skill_view(name, file_path=...)]"
                  Result.succeed(text + listing)
                }
              case Absent => Result.fail("SKILL.md missing")
            }
    }

  private def linkedFiles(skill: Skill): List[String] < Sync =
    Kyo.foreach(Skill.supportDirs.toList.sorted) { sub =>
      Fs.list(skill.dir.resolve(sub)).map(_.map(p => s"$sub/${p.getFileName}"))
    }.map(_.flatten.toList)

  /** skill_manage operations (create / patch / delete / write_file /
    * remove_file), writing to the local skills dir only.
    */
  def manage(
      action: String,
      name: String,
      content: Maybe[String],
      category: Maybe[String],
      oldString: Maybe[String],
      newString: Maybe[String],
      filePath: Maybe[String],
      fileContent: Maybe[String]
  ): Result[String, String] < Sync =
    val slug = name.toLowerCase.replaceAll("[^a-z0-9-]+", "-").stripPrefix("-").stripSuffix("-")
    action match
      case "create" =>
        content match
          case Absent => Result.fail("create requires content")
          case Present(body) =>
            val cat = category.getOrElse("general")
            val dir = localDir.resolve(cat).resolve(slug)
            val withFrontmatter =
              if body.startsWith("---") then body
              else s"---\nname: $slug\ndescription: \"$slug skill.\"\ncreated_by: \"agent\"\n---\n\n$body"
            Fs.writeString(dir.resolve("SKILL.md"), withFrontmatter)
              .map(_ => Result.succeed(s"created skill $slug in $cat"))
      case "patch" =>
        (oldString, newString) match
          case (Present(o), Present(n)) =>
            find(name).map {
              case Absent => Result.fail(s"skill not found: $name")
              case Present(skill) =>
                val md = skill.dir.resolve("SKILL.md")
                Fs.readString(md).map {
                  case Present(text) if text.contains(o) =>
                    Fs.writeString(md, text.replace(o, n)).map(_ => Result.succeed(s"patched $name"))
                  case Present(_) => Result.fail("old_string not found in SKILL.md")
                  case Absent     => Result.fail("SKILL.md missing")
                }
            }
          case _ => Result.fail("patch requires old_string and new_string")
      case "delete" =>
        find(name).map {
          case Absent => Result.fail(s"skill not found: $name")
          case Present(skill) if skill.external => Result.fail("external skills are read-only")
          case Present(skill) =>
            Sync.defer {
              def rm(p: Path): Unit =
                if java.nio.file.Files.isDirectory(p) then
                  import scala.jdk.CollectionConverters.*
                  val s = java.nio.file.Files.list(p)
                  try s.iterator.asScala.toList.foreach(rm)
                  finally s.close()
                java.nio.file.Files.deleteIfExists(p)
                ()
              rm(skill.dir)
              Result.succeed(s"deleted skill $name")
            }
        }
      case "write_file" =>
        (filePath, fileContent) match
          case (Present(rel), Present(text)) =>
            find(name).map {
              case Absent => Result.fail(s"skill not found: $name")
              case Present(skill) if skill.external => Result.fail("external skills are read-only")
              case Present(skill) =>
                val target = skill.dir.resolve(rel).normalize
                if !target.startsWith(skill.dir) then Result.fail("file_path escapes the skill directory")
                else Fs.writeString(target, text).map(_ => Result.succeed(s"wrote $rel in $name"))
            }
          case _ => Result.fail("write_file requires file_path and file_content")
      case "remove_file" =>
        filePath match
          case Present(rel) =>
            find(name).map {
              case Absent => Result.fail(s"skill not found: $name")
              case Present(skill) if skill.external => Result.fail("external skills are read-only")
              case Present(skill) =>
                val target = skill.dir.resolve(rel).normalize
                if !target.startsWith(skill.dir) then Result.fail("file_path escapes the skill directory")
                else Fs.delete(target).map(_ => Result.succeed(s"removed $rel from $name"))
            }
          case Absent => Result.fail("remove_file requires file_path")
      case other => Result.fail(s"unknown action: $other")
  end manage
end SkillStore

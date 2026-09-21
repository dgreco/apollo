package apollo.tools

import apollo.config.Fs
import apollo.util.Jx.*
import java.nio.file.{Files, Path, Paths}
import kyo.*
import kyo.Structure.Value
import scala.jdk.CollectionConverters.*
import scala.util.matching.Regex

/** File-operation tools (`file` toolset): read_file, write_file, patch,
  * search_files — wire schemas identical to upstream `tools/file_tools.py`.
  */
object FileTools:

  private val maxChars = 100_000

  val entries: List[ToolEntry] = List(
    ToolEntry(
      name = "read_file",
      toolset = "file",
      description =
        "Read a text file with line numbers and pagination. Returns lines formatted as LINE_NUM|CONTENT. " +
          "Use offset/limit to page through large files.",
      parametersJson = """{"type":"object","properties":{
        "path":{"type":"string","description":"Path to the file to read"},
        "offset":{"type":"integer","description":"1-based line to start from","default":1,"minimum":1},
        "limit":{"type":"integer","description":"Maximum lines to return","default":2000,"maximum":2000}
      },"required":["path"]}""".replaceAll("\n\\s*", ""),
      emoji = "📖",
      maxResultChars = maxChars,
      handler = readFile
    ),
    ToolEntry(
      name = "write_file",
      toolset = "file",
      description = "Write content to a file (full overwrite). Creates parent directories as needed.",
      parametersJson = """{"type":"object","properties":{
        "path":{"type":"string","description":"Path to the file to write"},
        "content":{"type":"string","description":"Complete file content"}
      },"required":["path","content"]}""".replaceAll("\n\\s*", ""),
      emoji = "✍️",
      maxResultChars = maxChars,
      handler = writeFile
    ),
    ToolEntry(
      name = "patch",
      toolset = "file",
      description =
        "Find-and-replace edit in a file. old_string must match exactly once unless replace_all is set; " +
          "whitespace-tolerant fallback matching is applied when an exact match fails. Returns a unified diff.",
      parametersJson = """{"type":"object","properties":{
        "path":{"type":"string","description":"Path to the file to edit"},
        "old_string":{"type":"string","description":"Text to replace"},
        "new_string":{"type":"string","description":"Replacement text"},
        "replace_all":{"type":"boolean","description":"Replace every occurrence","default":false}
      },"required":["path","old_string","new_string"]}""".replaceAll("\n\\s*", ""),
      emoji = "🔧",
      maxResultChars = maxChars,
      handler = patch
    ),
    ToolEntry(
      name = "search_files",
      toolset = "file",
      description =
        "Search file contents by regex (target=content) or find files by name pattern (target=files).",
      parametersJson = """{"type":"object","properties":{
        "pattern":{"type":"string","description":"Regex (content) or glob (files) pattern"},
        "target":{"type":"string","enum":["content","files"],"default":"content"},
        "path":{"type":"string","description":"Directory to search","default":"."},
        "file_glob":{"type":"string","description":"Only search files matching this glob"},
        "limit":{"type":"integer","default":50},
        "offset":{"type":"integer","default":0},
        "output_mode":{"type":"string","enum":["content","files_only","count"],"default":"content"},
        "context":{"type":"integer","description":"Context lines around each match","default":0}
      },"required":["pattern"]}""".replaceAll("\n\\s*", ""),
      emoji = "🔎",
      maxResultChars = maxChars,
      handler = searchFiles
    )
  )

  private def resolvePath(raw: String, ctx: ToolContext): Path =
    val expanded = Fs.expand(raw, ctx.config.env.get)
    val p        = Paths.get(expanded)
    if p.isAbsolute then p.normalize else ctx.cwd.resolve(p).normalize

  // --- read_file ----------------------------------------------------------

  private def readFile(args: Value, ctx: ToolContext): ToolOutcome < (Sync & Async) =
    val path   = (args / "path").asStr.getOrElse("")
    val offset = (args / "offset").asLong.map(_.toInt).getOrElse(1).max(1)
    val limit  = (args / "limit").asLong.map(_.toInt).getOrElse(2000).min(2000).max(1)
    if path.isEmpty then ToolOutcome.Error("missing required parameter: path")
    else
      val file = resolvePath(path, ctx)
      Fs.readString(file).map {
        case Absent => ToolOutcome.Error(s"file not found: $file")
        case Present(content) =>
          val lines = content.split("\n", -1)
          val page  = lines.iterator.zipWithIndex.slice(offset - 1, offset - 1 + limit)
          val body  = page.map((line, i) => s"${i + 1}|$line").mkString("\n")
          val note =
            if lines.length > offset - 1 + limit then
              s"\n[showing lines $offset-${offset + limit - 1} of ${lines.length}; continue with offset=${offset + limit}]"
            else ""
          ToolOutcome.Ok(if body.isEmpty then "[empty file]" else body + note)
      }

  // --- write_file ---------------------------------------------------------

  private def writeFile(args: Value, ctx: ToolContext): ToolOutcome < (Sync & Async) =
    ((args / "path").asStr, (args / "content").asStr) match
      case (Present(path), Present(content)) =>
        val file = resolvePath(path, ctx)
        ctx.approvals.checkInstructionWrite(List(file), ctx.ui).map {
          case Result.Failure(err) => ToolOutcome.Error(err)
          case _                   =>
            Fs.writeString(file, content).map { _ =>
              ToolOutcome.Ok(s"""{"success":true,"path":"$file","bytes":${content.getBytes("UTF-8").length}}""")
            }
        }
      case _ => ToolOutcome.Error("missing required parameters: path, content")

  // --- patch --------------------------------------------------------------

  private def patch(args: Value, ctx: ToolContext): ToolOutcome < (Sync & Async) =
    ((args / "path").asStr, (args / "old_string").asStr, (args / "new_string").asStr) match
      case (Present(path), Present(oldString), Present(newString)) =>
        val replaceAll = (args / "replace_all").asBool.getOrElse(false)
        val file       = resolvePath(path, ctx)
        ctx.approvals.checkInstructionWrite(List(file), ctx.ui).map {
          case Result.Failure(err) => ToolOutcome.Error(err)
          case _                   =>
            Fs.readString(file).map {
              case Absent => ToolOutcome.Error(s"file not found: $file")
              case Present(content) =>
                applyPatch(content, oldString, newString, replaceAll) match
                  case Result.Failure(err) => ToolOutcome.Error(err)
                  case Result.Success(updated) =>
                    Fs.writeString(file, updated).map { _ =>
                      ToolOutcome.Ok(unifiedDiff(file.toString, content, updated))
                    }
                  case _ => ToolOutcome.Error("patch failed")
            }
        }
      case _ => ToolOutcome.Error("missing required parameters: path, old_string, new_string")

  /** Exact match first, then whitespace-tolerant fallbacks (a subset of
    * the upstream harness's nine fuzzy strategies: trimmed-line and collapsed-whitespace
    * matching), applied only when they identify an unambiguous region.
    */
  private[tools] def applyPatch(
      content: String,
      oldString: String,
      newString: String,
      replaceAll: Boolean
  ): Result[String, String] =
    if oldString.isEmpty then Result.fail("old_string must not be empty")
    else if oldString == newString then Result.fail("old_string and new_string are identical")
    else
      val occurrences = countOccurrences(content, oldString)
      if occurrences == 0 then
        fuzzyRegion(content, oldString) match
          case Present((start, end)) =>
            Result.succeed(content.substring(0, start) + newString + content.substring(end))
          case Absent =>
            Result.fail("old_string not found in file (even with whitespace-tolerant matching)")
      else if occurrences > 1 && !replaceAll then
        Result.fail(s"old_string matches $occurrences locations; provide more context or set replace_all")
      else if replaceAll then Result.succeed(content.replace(oldString, newString))
      else
        val idx = content.indexOf(oldString)
        Result.succeed(content.substring(0, idx) + newString + content.substring(idx + oldString.length))

  private def countOccurrences(haystack: String, needle: String): Int =
    var count = 0
    var idx   = haystack.indexOf(needle)
    while idx >= 0 do
      count += 1
      idx = haystack.indexOf(needle, idx + needle.length)
    count

  /** Line-based fuzzy match: finds the unique run of lines whose trimmed (or
    * whitespace-collapsed) forms equal the pattern's. Returns the exact char
    * region to replace.
    */
  private def fuzzyRegion(content: String, oldString: String): Maybe[(Int, Int)] =
    val contentLines = content.split("\n", -1)
    val patternLines = oldString.split("\n", -1)
    def normalize(collapse: Boolean)(s: String) =
      if collapse then s.trim.replaceAll("\\s+", " ") else s.trim

    def attempt(collapse: Boolean): Maybe[(Int, Int)] =
      val normPattern = patternLines.map(normalize(collapse))
      val matches =
        (0 to contentLines.length - patternLines.length).filter { start =>
          (0 until patternLines.length).forall(i => normalize(collapse)(contentLines(start + i)) == normPattern(i))
        }
      matches.toList match
        case start :: Nil =>
          val before = contentLines.take(start).map(_.length + 1).sum
          val length = contentLines.slice(start, start + patternLines.length).map(_.length).sum
            + (patternLines.length - 1)
          Present((before, before + length))
        case _ => Absent

    attempt(collapse = false).orElse(attempt(collapse = true))
  end fuzzyRegion

  /** Minimal unified diff (full-context replacement style). */
  private[tools] def unifiedDiff(path: String, before: String, after: String): String =
    val b   = before.split("\n", -1)
    val a   = after.split("\n", -1)
    val pre = b.zip(a).takeWhile(_ == _).length
    val sufMax = math.min(b.length, a.length) - pre
    val suf = b.reverse.zip(a.reverse).takeWhile(_ == _).length.min(sufMax)
    val removed = b.slice(pre, b.length - suf)
    val added   = a.slice(pre, a.length - suf)
    val header  = s"--- $path\n+++ $path\n@@ -${pre + 1},${removed.length} +${pre + 1},${added.length} @@"
    val body    = removed.map("-" + _) ++ added.map("+" + _)
    (header +: body).mkString("\n")

  // --- search_files -------------------------------------------------------

  private def searchFiles(args: Value, ctx: ToolContext): ToolOutcome < (Sync & Async) =
    val pattern    = (args / "pattern").asStr.getOrElse("")
    val target     = (args / "target").asStr.getOrElse("content")
    val dir        = resolvePath((args / "path").asStr.getOrElse("."), ctx)
    val fileGlob   = (args / "file_glob").asStr
    val limit      = (args / "limit").asLong.map(_.toInt).getOrElse(50).max(1)
    val offset     = (args / "offset").asLong.map(_.toInt).getOrElse(0).max(0)
    val outputMode = (args / "output_mode").asStr.getOrElse("content")
    val context    = (args / "context").asLong.map(_.toInt).getOrElse(0).max(0)
    if pattern.isEmpty then ToolOutcome.Error("missing required parameter: pattern")
    else
      Sync.defer {
        if target == "files" then searchFileNames(dir, pattern, limit, offset)
        else searchContent(dir, pattern, fileGlob, limit, offset, outputMode, context)
      }

  private val skipDirs = Set(".git", "node_modules", "target", ".venv", "venv", "__pycache__", ".idea", ".bloop")

  private def walk(dir: Path, max: Int = 20000): List[Path] =
    val out = List.newBuilder[Path]
    var n   = 0
    def go(d: Path): Unit =
      if n < max && Files.isDirectory(d) && !skipDirs.contains(d.getFileName.toString) then
        val stream = Files.list(d)
        val children =
          try stream.iterator.asScala.toList.sortBy(_.getFileName.toString)
          finally stream.close()
        children.foreach { c =>
          if n < max then
            if Files.isDirectory(c) then go(c)
            else
              out += c
              n += 1
        }
    go(dir)
    out.result()

  private def globToRegex(glob: String): Regex =
    ("(?s)" + glob.flatMap {
      case '*'   => ".*"
      case '?'   => "."
      case '.'   => "\\."
      case '{'   => "("
      case '}'   => ")"
      case ','   => "|"
      case c     => Regex.quote(c.toString)
    } + "$").r

  private def searchFileNames(dir: Path, pattern: String, limit: Int, offset: Int): ToolOutcome =
    val regex = globToRegex(if pattern.contains("*") || pattern.contains("?") then pattern else s"*$pattern*")
    val hits = walk(dir).filter(p => regex.findFirstIn(p.toString).isDefined)
    val page = hits.slice(offset, offset + limit)
    if page.isEmpty then ToolOutcome.Ok("no matching files")
    else ToolOutcome.Ok(page.mkString("\n") + (if hits.length > offset + limit then s"\n[${hits.length} total]" else ""))

  private def searchContent(
      dir: Path,
      pattern: String,
      fileGlob: Maybe[String],
      limit: Int,
      offset: Int,
      outputMode: String,
      context: Int
  ): ToolOutcome =
    val regex =
      try pattern.r
      catch case e: Exception => return ToolOutcome.Error(s"invalid regex: ${e.getMessage}")
    val globRegex = fileGlob.map(globToRegex)
    val files = walk(dir).filter { p =>
      globRegex.map(r => r.findFirstIn(p.getFileName.toString).isDefined).getOrElse(true)
    }
    val results = List.newBuilder[String]
    var matchCount = 0
    var fileCount  = 0
    val matchedFiles = List.newBuilder[String]
    files.foreach { file =>
      if matchCount < offset + limit + 1000 then
        val content =
          try
            val bytes = Files.readAllBytes(file)
            if bytes.take(1024).contains(0.toByte) then "" // binary
            else new String(bytes, "UTF-8")
          catch case _: Exception => ""
        if content.nonEmpty then
          val lines   = content.split("\n", -1)
          val matched = lines.zipWithIndex.filter((l, _) => regex.findFirstIn(l).isDefined)
          if matched.nonEmpty then
            fileCount += 1
            matchedFiles += file.toString
            matched.foreach { (line, i) =>
              matchCount += 1
              if matchCount > offset && matchCount <= offset + limit then
                if context > 0 then
                  val lo = (i - context).max(0)
                  val hi = (i + context).min(lines.length - 1)
                  results += s"$file:${i + 1}:\n" + (lo to hi).map(j => s"  ${j + 1}|${lines(j)}").mkString("\n")
                else results += s"$file:${i + 1}:$line"
            }
    }
    outputMode match
      case "count"      => ToolOutcome.Ok(s"$matchCount matches in $fileCount files")
      case "files_only" => ToolOutcome.Ok(matchedFiles.result().distinct.take(limit).mkString("\n"))
      case _ =>
        val out = results.result()
        if out.isEmpty then ToolOutcome.Ok("no matches")
        else ToolOutcome.Ok(out.mkString("\n") + (if matchCount > offset + limit then s"\n[$matchCount total matches]" else ""))
  end searchContent
end FileTools

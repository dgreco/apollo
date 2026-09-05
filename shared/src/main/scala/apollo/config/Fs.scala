package apollo.config

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths, StandardCopyOption}
import kyo.*
import scala.jdk.CollectionConverters.*

/** Minimal effectful filesystem facade over `java.nio.file` (available on
  * both the JVM and Scala Native javalib).
  */
object Fs:

  def userHome: String =
    java.lang.System.getProperty("user.home", ".")

  /** Expands a leading `~` and `${VAR}`/`$VAR` environment references. */
  def expand(path: String, env: String => Maybe[String]): String =
    val tilde =
      if path == "~" then userHome
      else if path.startsWith("~/") then userHome + path.drop(1)
      else path
    val varPattern = """\$\{([A-Za-z_][A-Za-z0-9_]*)\}""".r
    varPattern.replaceAllIn(tilde, m => env(m.group(1)).getOrElse(""))

  def readString(path: Path): Maybe[String] < Sync =
    Sync.defer {
      if Files.isRegularFile(path) then
        Present(new String(Files.readAllBytes(path), StandardCharsets.UTF_8))
      else Absent
    }

  def readBytes(path: Path): Maybe[Array[Byte]] < Sync =
    Sync.defer {
      if Files.isRegularFile(path) then Present(Files.readAllBytes(path))
      else Absent
    }

  def writeString(path: Path, content: String): Unit < Sync =
    Sync.defer {
      val parent = path.getParent
      if parent != null then Files.createDirectories(parent)
      Files.write(path, content.getBytes(StandardCharsets.UTF_8))
      ()
    }

  /** Write-then-rename so readers never observe a torn file. */
  def writeStringAtomic(path: Path, content: String): Unit < Sync =
    Sync.defer {
      val parent = path.getParent
      if parent != null then Files.createDirectories(parent)
      val tmp = path.resolveSibling(path.getFileName.toString + ".tmp")
      Files.write(tmp, content.getBytes(StandardCharsets.UTF_8))
      Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
      ()
    }

  def appendString(path: Path, content: String): Unit < Sync =
    Sync.defer {
      val parent = path.getParent
      if parent != null then Files.createDirectories(parent)
      Files.write(
        path,
        content.getBytes(StandardCharsets.UTF_8),
        java.nio.file.StandardOpenOption.CREATE,
        java.nio.file.StandardOpenOption.APPEND
      )
      ()
    }

  def exists(path: Path): Boolean < Sync       = Sync.defer(Files.exists(path))
  def isDirectory(path: Path): Boolean < Sync  = Sync.defer(Files.isDirectory(path))
  def createDirs(path: Path): Unit < Sync      = Sync.defer { Files.createDirectories(path); () }
  def delete(path: Path): Unit < Sync          = Sync.defer { Files.deleteIfExists(path); () }

  def list(path: Path): List[Path] < Sync =
    Sync.defer {
      if Files.isDirectory(path) then
        val stream = Files.list(path)
        try stream.iterator.asScala.toList.sortBy(_.getFileName.toString)
        finally stream.close()
      else Nil
    }

  def resolve(first: String, more: String*): Path = Paths.get(first, more*)
end Fs

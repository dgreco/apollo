// SPDX-FileCopyrightText: 2026 David Greco <greco@acm.org>
//
// SPDX-License-Identifier: Apache-2.0

package apollo.config

import java.nio.file.{Files, Path, Paths}
import kyo.*

/** The the upstream harness home directory and the well-known paths inside it. */
final case class ApolloPaths(home: Path):
  def configYaml: Path  = home.resolve("config.yaml")
  def dotenv: Path      = home.resolve(".env")
  def opEnv: Path       = home.resolve(".op.env")
  def skillsDir: Path   = home.resolve("skills")
  def logsDir: Path     = home.resolve("logs")
  def sessionsDir: Path = home.resolve("sessions")
  def memoryMd: Path    = home.resolve("memories").resolve("MEMORY.md")
  def userMd: Path      = home.resolve("memories").resolve("USER.md")
  def cronJobs: Path    = home.resolve("cron").resolve("jobs.json")
  def authJson: Path    = home.resolve("auth.json")
  def mcpTokensDir: Path = home.resolve("mcp-tokens")

/** Home-directory resolution, following the upstream algorithm
  * (`the upstream constants module` + upstream `main.py::_apply_profile_override` +
  * upstream `profiles.py::resolve_profile_env`) but with this app's own
  * identity — only `APOLLO_HOME` is consulted, so a shell configured for
  * the upstream Python harness never steers this app into its directory:
  *
  *   - home = `$APOLLO_HOME` (non-empty after strip) → platform default
  *     (`%LOCALAPPDATA%\apollo` on Windows, `~/.apollo` elsewhere)
  *   - an explicit `-p/--profile` wins; otherwise, when `APOLLO_HOME` isn't
  *     already profile-shaped (immediate parent named `profiles`) and the
  *     process isn't a supervised gateway child, the sticky
  *     `<root>/active_profile` file supplies the profile
  *   - profile names must match `^[a-z0-9][a-z0-9_-]{0,63}$`; `default`
  *     maps to the root itself; a named profile requires
  *     `<root>/profiles/<name>` to exist
  */
object ApolloPaths:

  private val profileNameRe = "^[a-z0-9][a-z0-9_-]{0,63}$".r

  def platformDefaultHome: Path =
    if java.lang.System.getProperty("os.name", "").toLowerCase.contains("win") then
      sys.env.get("LOCALAPPDATA").map(_.trim).filter(_.nonEmpty) match
        case Some(appData) => Paths.get(appData, "apollo")
        case None          => Paths.get(Fs.userHome, "AppData", "Local", "apollo")
    else Paths.get(Fs.userHome, ".apollo")

  private def envHome: Option[Path] =
    sys.env.get("APOLLO_HOME").map(_.trim).filter(_.nonEmpty).map(Paths.get(_))

  /** `the upstream root-resolution rule`: `<root>` when home is `<root>/profiles/<x>`. */
  def defaultRoot: Path =
    envHome match
      case Some(p) if p.getParent != null && p.getParent.getFileName != null
          && p.getParent.getFileName.toString == "profiles" =>
        p.getParent.getParent
      case Some(p) =>
        // Under the native home (normal or profile mode) the root is the
        // native home; a custom root IS the root.
        val native = platformDefaultHome.toAbsolutePath.normalize
        if p.toAbsolutePath.normalize.startsWith(native) then native else p
      case None => platformDefaultHome

  private def supervisedGatewayChild: Boolean =
    sys.env.get("APOLLO_SUPERVISED_CHILD").exists(_.nonEmpty)

  /** Resolves the home honoring the profile flag and the sticky
    * `active_profile` file. Fails (like the upstream harness exits 1) on an invalid
    * profile name or a missing profile directory.
    */
  def resolve(profileFlag: Maybe[String]): Result[String, ApolloPaths] < Sync =
    Sync.defer {
      val flagName = profileFlag.map(_.trim).filter(_.nonEmpty)

      // APOLLO_HOME already profile-shaped and no explicit flag: trust it.
      val homeIsProfileShaped = envHome.exists(p =>
        p.getParent != null && p.getParent.getFileName != null
          && p.getParent.getFileName.toString == "profiles"
      )
      if flagName.isEmpty && homeIsProfileShaped then
        Result.succeed(ApolloPaths(envHome.get))
      else
        // Sticky active_profile (unless supervised gateway child).
        val sticky: Option[String] =
          if flagName.nonEmpty || supervisedGatewayChild then None
          else
            val activePath = defaultRoot.resolve("active_profile")
            try
              if Files.isRegularFile(activePath) then
                Option(new String(Files.readAllBytes(activePath), "UTF-8").trim)
                  .filter(n => n.nonEmpty && n != "default")
              else None
            catch case _: Exception => None

        flagName.toOption.orElse(sticky) match
          case None => Result.succeed(ApolloPaths(envHome.getOrElse(platformDefaultHome)))
          case Some(name) =>
            if !profileNameRe.matches(name) && name != "default" then
              Result.fail(s"invalid profile name: '$name' (must match ${profileNameRe.regex})")
            else if name == "default" then Result.succeed(ApolloPaths(defaultRoot))
            else
              val profileDir = defaultRoot.resolve("profiles").resolve(name)
              if Files.isDirectory(profileDir) then Result.succeed(ApolloPaths(profileDir))
              else if sticky.contains(name) then
                // A corrupt/stale sticky file must never prevent startup
                // (upstream warns and uses the default).
                Result.succeed(ApolloPaths(envHome.getOrElse(platformDefaultHome)))
              else Result.fail(s"profile '$name' does not exist (expected $profileDir)")
    }
end ApolloPaths

/** Managed scope (upstream `managed_scope.py`): the IT-pushed immutable
  * layer at `$APOLLO_MANAGED_DIR` (honored only when non-empty AND the
  * directory exists) or `/etc/apollo`.
  * Its `config.yaml` deep-merges OVER
  * the user config (managed wins at the leaf); its `.env` loads last with
  * override. Fail-open: a malformed managed file never blocks startup.
  */
object ManagedScope:

  def managedDir: Option[Path] =
    val overridePath = sys.env.get("APOLLO_MANAGED_DIR").map(_.trim).filter(_.nonEmpty).map(Paths.get(_))
    val candidate = overridePath.getOrElse(Paths.get("/etc/apollo"))
    Option.when(Files.isDirectory(candidate))(candidate)

  def envFile: Maybe[Map[String, String]] < Sync =
    managedDir match
      case None => Absent
      case Some(dir) =>
        Fs.readString(dir.resolve(".env")).map(_.map(EnvFile.parse))

  def configNode: Maybe[org.virtuslab.yaml.Node] < Sync =
    managedDir match
      case None => Absent
      case Some(dir) =>
        Fs.readString(dir.resolve("config.yaml")).map {
          case Absent => Absent
          case Present(text) =>
            Yaml.parse(text) match
              case Result.Success(node) => Present(node)
              case _                    => Absent // fail-open, like the upstream harness
        }
end ManagedScope

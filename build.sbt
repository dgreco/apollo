// SPDX-FileCopyrightText: 2026 David Greco <greco@acm.org>
//
// SPDX-License-Identifier: Apache-2.0

import scala.scalanative.build.*
import scala.sys.process.*

// ---------------------------------------------------------------------------
// apollo — a Scala 3 + Kyo agent harness
// Cross-built for JVM and Native.
// ---------------------------------------------------------------------------

val kyoVersion       = "1.0.0-RC6"
val scalaYamlVersion = "0.3.3"
val jlineVersion     = "4.4.2"
val munitVersion     = "1.3.6"
val archunitVersion  = "1.5.0"
val sqliteVersion    = "3.47.1.0"

inThisBuild(
  List(
    scalaVersion  := "3.9.0",
    organization  := "dev.apollo",
    version       := "0.1.0-SNAPSHOT",
    homepage      := Some(url("https://github.com/dgreco/apollo")),
    licenses      := List("Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0")),
    startYear     := Some(2026),
    developers    := List(
      Developer("dgreco", "David Greco", "greco@acm.org", url("https://github.com/dgreco"))
    ),
    scmInfo       := Some(
      ScmInfo(
        url("https://github.com/dgreco/apollo"),
        "scm:git:https://github.com/dgreco/apollo.git",
        "scm:git:git@gitlab.davidgreco.it:dgreco/apollo.git"
      )
    ),
    scalacOptions ++= List("-deprecation", "-feature", "-unchecked", "-Wunused:imports")
  )
)

/** Locates a linkable OpenSSL 3.x for kyo-http's native TLS shim (same probe
  * order Kyo's own build uses). macOS ships no linkable libssl, so Homebrew /
  * MacPorts / pkgsrc locations are searched before the Linux defaults.
  */
def opensslPaths: Option[(String, String)] =
  List(
    "/opt/homebrew/opt/openssl@3",
    "/usr/local/opt/openssl@3",
    "/opt/local",
    "/usr/local",
    "/usr"
  ).collectFirst {
    case p if file(s"$p/include/openssl/ssl.h").exists && (file(s"$p/lib").exists || file(s"$p/lib64").exists) =>
      val lib = if (file(s"$p/lib64").exists) s"$p/lib64" else s"$p/lib"
      (s"$p/include", lib)
  }

/** Native link flags for kyo-net's io_uring backend (Linux only). kyo-net
  * statically links liburing — its shipped
  * `META-INF/kyo-ffi/native-link-flags/kyo-net.flags` carries
  * `-Wl,-Bstatic -luring` — but apollo has no kyo-ffi sbt plugin to apply that
  * file, so the flag is added here whenever a liburing dev package (its header)
  * is present. macOS ships none: there the io_uring translation unit compiles to
  * nothing and the symbols are never referenced, so this stays empty and the
  * local build is untouched. Requires `liburing-dev` on the Linux build host
  * (see .gitlab-ci.yml).
  */
def liburingLinkOptions: List[String] =
  val hasHeader = List("/usr/include", "/usr/local/include")
    .exists(p => file(s"$p/liburing.h").exists)
  if (hasHeader) List("-Wl,-Bstatic", "-luring", "-Wl,-Bdynamic") else Nil

lazy val agent = crossProject(JVMPlatform, NativePlatform)
  .crossType(CrossType.Full)
  .in(file("."))
  .settings(
    name := "apollo",
    libraryDependencies ++= List(
      "io.getkyo"     %%  "kyo-core"        % kyoVersion,
      "io.getkyo"     %%  "kyo-prelude"     % kyoVersion,
      "io.getkyo"     %%  "kyo-data"        % kyoVersion,
      "io.getkyo"     %%  "kyo-http"        % kyoVersion,
      "io.getkyo"     %%  "kyo-schema-json" % kyoVersion,
      "io.getkyo"     %%  "kyo-stats-registry" % kyoVersion,
      "org.virtuslab" %%  "scala-yaml"      % scalaYamlVersion,
      "org.scalameta" %%  "munit"           % munitVersion % Test
    ),
    // The MCP manager (and its dynamic tool registry) is process-global;
    // suites that start/stop it cannot run interleaved.
    Test / parallelExecution := false,
    Compile / mainClass := Some("apollo.Main")
  )
  .jvmSettings(
    libraryDependencies += "org.jline" % "jline" % jlineVersion,
    // ArchUnit reads JVM bytecode, so the architecture suite is JVM-only — but
    // it governs the whole shared codebase, which is where all but three
    // classes live (see ArchitectureSuite).
    libraryDependencies += "com.tngtech.archunit" % "archunit" % archunitVersion % Test,
    // JVM-only: SQLite (FTS5 compiled in) backs session search. Scala Native
    // has no bundled SQLite, so it keeps the pure-Scala transcript scan and
    // this dependency never reaches the native binary.
    libraryDependencies += "org.xerial" % "sqlite-jdbc" % sqliteVersion,
    assembly / assemblyJarName := "apollo.jar",
    assembly / mainClass       := Some("apollo.Main"),
    // ServiceLoader/SPI resources under META-INF must survive assembly: JLine
    // discovers its terminal backends via META-INF/jline/providers/* and a
    // blanket META-INF discard silently downgrades the packaged jar to a dumb
    // terminal while `sbt run` keeps working.
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", "services", _*)   => MergeStrategy.concat
      case PathList("META-INF", "jline", _*)      => MergeStrategy.concat
      case PathList("META-INF", "native", _*)     => MergeStrategy.first
      case PathList("META-INF", "versions", _*)   => MergeStrategy.first
      case PathList("META-INF", _*)               => MergeStrategy.discard
      case _                                      => MergeStrategy.first
    }
  )
  .nativeSettings(
    nativeConfig ~= { cfg =>
      val withSsl = opensslPaths match {
        case Some((include, lib)) =>
          cfg
            .withCompileOptions(cfg.compileOptions ++ List(s"-I$include"))
            .withLinkingOptions(cfg.linkingOptions ++ List(s"-L$lib", "-lssl", "-lcrypto"))
        case None => cfg
      }
      // kyo-net's io_uring backend needs -luring on Linux (no-op on macOS).
      val withUring = liburingLinkOptions match {
        case Nil   => withSsl
        case flags => withSsl.withLinkingOptions(withSsl.linkingOptions ++ flags)
      }
      // Embed classpath resources into the binary so `getResourceAsStream`
      // works on Native (the test suite loads the Hermes config fixture that
      // way; default-off embedding otherwise returns null there).
      withUring.withLTO(LTO.none).withMode(Mode.debug).withEmbedResources(true)
    }
  )

lazy val agentJVM    = agent.jvm
lazy val agentNative = agent.native

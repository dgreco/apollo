import scala.scalanative.build.*
import scala.sys.process.*

// ---------------------------------------------------------------------------
// jupyter-agent — a Scala 3 + Kyo agent harness
// Cross-built for JVM and Native.
// ---------------------------------------------------------------------------

val kyoVersion       = "1.0.0-RC6"
val scalaYamlVersion = "0.3.3"
val jlineVersion     = "4.4.2"
val munitVersion     = "1.3.6"
val sqliteVersion    = "3.47.1.0"

inThisBuild(
  List(
    scalaVersion  := "3.9.0",
    organization  := "dev.apollo",
    version       := "0.1.0-SNAPSHOT",
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
      // Embed classpath resources into the binary so `getResourceAsStream`
      // works on Native (the test suite loads the Hermes config fixture that
      // way; default-off embedding otherwise returns null there).
      withSsl.withLTO(LTO.none).withMode(Mode.debug).withEmbedResources(true)
    }
  )

lazy val agentJVM    = agent.jvm
lazy val agentNative = agent.native

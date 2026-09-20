package apollo.browser

import apollo.config.ApolloConfig
import apollo.http.{HttpError, Transport}
import apollo.util.Jx
import kyo.*

/** Headless-Chrome lifecycle for the browser tool: locate a Chrome/Chromium
  * binary, either connect to an already-running instance (`CHROME_CDP_URL`) or
  * launch one, and resolve a page target's debugger WebSocket URL.
  *
  * Binary discovery and launch args are pure/testable; the launch + readiness
  * poll are live-only (no browser in CI). A launched instance is cached
  * process-globally so tool calls reuse it. */
object Chrome:

  /** Common Chrome/Chromium/Edge locations and PATH names, best-first. */
  val chromeCandidates: List[String] = List(
    "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
    "/Applications/Chromium.app/Contents/MacOS/Chromium",
    "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge",
    "google-chrome-stable", "google-chrome", "chromium", "chromium-browser", "chrome", "microsoft-edge")

  /** Headless launch args exposing a CDP endpoint on `port`. */
  def launchArgs(binary: String, port: Int, userDataDir: String): List[String] =
    List(binary, "--headless=new", s"--remote-debugging-port=$port",
      s"--user-data-dir=$userDataDir", "--no-first-run", "--no-default-browser-check",
      "--disable-gpu", "--disable-dev-shm-usage", "--remote-allow-origins=*", "about:blank")

  /** First candidate that `exists` accepts (absolute path present, or on PATH). */
  def findChrome(candidates: List[String], exists: String => Boolean): Maybe[String] =
    Maybe.fromOption(candidates.find(exists))

  /** Is `name` an executable on PATH (native-safe: no subprocess). */
  def onPath(name: String, path: String): Boolean =
    path.split(java.io.File.pathSeparator).iterator.filter(_.nonEmpty).exists { dir =>
      java.nio.file.Files.isExecutable(java.nio.file.Paths.get(dir, name))
    }

  private def defaultExists(c: String): Boolean =
    if c.contains("/") then java.nio.file.Files.exists(java.nio.file.Paths.get(c))
    else onPath(c, Option(java.lang.System.getenv("PATH")).getOrElse(""))

  // --- live ---------------------------------------------------------------

  private val launched = new java.util.concurrent.atomic.AtomicReference[Maybe[String]](Absent)

  /** Is the browser tool usable: an explicit CDP URL, or `browser.enabled` with
    * a discoverable Chrome. */
  def available(config: ApolloConfig): Boolean =
    config.env.get("CHROME_CDP_URL").orElse(config.env.get("BROWSER_CDP_URL")).nonEmpty ||
      (config.browserEnabled && findChrome(chromeCandidates, defaultExists).nonEmpty)

  /** The CDP base URL: an explicit `CHROME_CDP_URL`/`BROWSER_CDP_URL`, else a
    * launched (and cached) headless Chrome. */
  def endpoint(config: ApolloConfig): Result[String, String] < (Sync & Async) =
    config.env.get("CHROME_CDP_URL").orElse(config.env.get("BROWSER_CDP_URL")) match
      case Present(u) => Result.succeed(u.stripSuffix("/"))
      case Absent     => ensureLaunched(config)

  /** A page target's debugger WS URL from `<base>/json`. */
  def pageWs(cdpBase: String): Result[String, String] < (Sync & Async) =
    Abort.run[HttpError](Transport.getJson(s"$cdpBase/json", Nil, 10.seconds)).map {
      case Result.Success(body) =>
        Jx.parse(body) match
          case Result.Success(j) => Cdp.firstPageWs(j) match
            case Present(w) => Result.succeed(w)
            case Absent     => Result.fail("cdp: no page target at " + cdpBase)
          case _ => Result.fail("cdp: unparseable /json listing")
      case Result.Failure(e) => Result.fail(s"cdp: /json fetch failed: ${e.getMessage}")
      case Result.Panic(e)   => Result.fail(s"cdp: /json fetch failed: ${String.valueOf(e.getMessage)}")
    }

  private def ensureLaunched(config: ApolloConfig): Result[String, String] < (Sync & Async) =
    launched.get match
      case Present(b) => Result.succeed(b)
      case Absent =>
        val port = config.env.get("CHROME_DEBUG_PORT").flatMap(p => Maybe.fromOption(p.toIntOption)).getOrElse(9222)
        findChrome(chromeCandidates, defaultExists) match
          case Absent =>
            Result.fail("no Chrome/Chromium found; install one or set CHROME_CDP_URL to a running instance")
          case Present(bin) =>
            val base = s"http://127.0.0.1:$port"
            Sync.defer(java.nio.file.Files.createTempDirectory("apollo-chrome").toString).map { dir =>
              Abort.run[CommandException](
                Command(launchArgs(bin, port, dir)*).redirectErrorStream(true).spawnUnscoped
              ).map { spawned =>
                val out: Result[String, String] < (Sync & Async) = spawned match
                  case Result.Success(_) =>
                    waitReady(base, 40).map { ready =>
                      if ready then { launched.set(Present(base)); Result.succeed(base) }
                      else Result.fail(s"chrome did not expose a CDP endpoint on $base")
                    }
                  case Result.Failure(e) => Result.fail(s"failed to launch chrome: ${e.getMessage}")
                  case Result.Panic(e)   => Result.fail(s"failed to launch chrome: ${String.valueOf(e.getMessage)}")
                out
              }
            }

  /** Polls `<base>/json/version` until it answers (or `tries` exhausted). */
  private def waitReady(base: String, tries: Int): Boolean < (Sync & Async) =
    if tries <= 0 then false
    else
      Abort.run[HttpError](Transport.getJson(s"$base/json/version", Nil, 2.seconds)).map {
        case Result.Success(_) => true
        case _                 => Async.sleep(250.millis).andThen(waitReady(base, tries - 1))
      }
end Chrome

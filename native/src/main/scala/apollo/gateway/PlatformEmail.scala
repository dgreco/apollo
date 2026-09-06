package apollo.gateway

import kyo.*

/** Native email transport: not supported. IMAP/SMTP need TLS sockets, which
  * Scala Native's javalib does not provide (and kyo-net RC6 exposes only a
  * low-level Unsafe transport). The email gateway therefore runs on the JVM
  * build; on Native it degrades to a clear error rather than a broken socket. */
object PlatformEmail:

  private val unsupported =
    "email gateway is only available on the JVM build (Native has no TLS-socket support)"

  def send(cfg: Email.Config, to: String, subject: String, body: String): Result[String, Unit] < (Sync & Async) =
    Result.fail(unsupported)

  def fetchUnseen(cfg: Email.Config): Result[String, List[Email.Msg]] < (Sync & Async) =
    Result.fail(unsupported)
end PlatformEmail

package apollo

import apollo.cli.Cli
import kyo.*

/** Entry point: parses the upstream-compatible command line and dispatches.
  * Bare `apollo` opens the chat REPL; `-z "..."` runs one shot;
  * subcommands cover the rest (see `apollo help`).
  */
object Main extends KyoApp:

  // Kyo's own libraries (kyo-http backend probes, IO backend selection) log
  // benign WARN lines ("IoBackend: selected 'openssl'; ...") through
  // `kyo.logs`, which would pollute the chat UI. The process-wide level is a
  // read-once flag, so default it to `error` BEFORE any Kyo effect runs —
  // but never override an explicit user choice (-Dkyo.Log.defaultLevel or
  // KYO_LOG_DEFAULTLEVEL restores the noisier levels for diagnosis).
  locally {
    val alreadySet =
      java.lang.System.getProperty("kyo.Log.defaultLevel") != null
        || java.lang.System.getenv("KYO_LOG_DEFAULTLEVEL") != null
    if !alreadySet then java.lang.System.setProperty("kyo.Log.defaultLevel", "error")
  }

  /** Quiet ambient logger for library code that logs via the Log local
    * (second layer of defense: the static flag above is latched at Log
    * class-init, which may precede this object's body on some platforms).
    */
  private def quietLog: Log =
    import AllowUnsafe.embrace.danger
    Log(Log.Unsafe.ConsoleLogger("kyo.logs", Log.Level.error))

  run {
    Log.let(quietLog) {
      Scope.run {
        Cli.run(args.toList)
      }
    }
  }
end Main

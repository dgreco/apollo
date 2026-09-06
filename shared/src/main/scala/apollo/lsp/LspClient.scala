package apollo.lsp

import apollo.config.{ApolloConfig, Fs}
import kyo.*

/** Live LSP client: spawns a language server for a file, runs the
  * initialize → didOpen handshake, and collects `publishDiagnostics` for that
  * file within a bounded window. Best-effort and live-only (needs the language
  * server installed); the framing/protocol it drives is the pure `Lsp` layer.
  * Mirrors Hermes's LSP-backed diagnostics. */
object LspClient:

  /** Diagnostics for `path`, or an error (no server / spawn failure). */
  def diagnose(path: String, config: ApolloConfig): Result[String, List[Lsp.Diagnostic]] < (Sync & Async) =
    if !config.lspEnabled then Result.fail("lsp is disabled (lsp.enabled=false)")
    else
      Lsp.serverFor(path, config) match
        case None => Result.fail(s"no language server configured for .${Lsp.extensionOf(path)}")
        case Some(server) =>
          val abs  = java.nio.file.Paths.get(path).toAbsolutePath.normalize
          val root = Option(abs.getParent).map(_.toString).getOrElse(".")
          val uri  = "file://" + abs.toString
          Fs.readString(abs).map { textOpt =>
            spawnAndDiagnose(server, root, uri, textOpt.getOrElse(""), config)
          }

  private def spawnAndDiagnose(
      server: Lsp.Server, root: String, uri: String, text: String, config: ApolloConfig
  ): Result[String, List[Lsp.Diagnostic]] < (Sync & Async) =
    Abort.run[CommandException](Command(server.argv*).cwd(Path(root)).pipeStdin.spawnUnscoped).map {
      case Result.Failure(e) => Result.fail(s"failed to launch ${server.argv.head}: ${e.getMessage}")
      case Result.Panic(e)   => Result.fail(String.valueOf(e.getMessage))
      case Result.Success(proc) =>
        import AllowUnsafe.embrace.danger
        val stdin = proc.unsafe.stdinJava
        def write(msg: String): Unit =
          stdin.synchronized { stdin.write(Lsp.encode(msg).getBytes("UTF-8")); stdin.flush() }

        val buf   = new StringBuilder
        var sent  = false                       // initialized + didOpen sent
        var diags = Option.empty[List[Lsp.Diagnostic]]

        def handleFrame(frame: String): Unit < (Sync & Async) =
          apollo.util.Jx.parse(frame) match
            case Result.Success(json) =>
              import apollo.util.Jx.*
              if !sent && (json / "id").asLong == Present(1L) && (json / "result").nonEmpty then
                sent = true
                Sync.defer { write(Lsp.initialized); write(Lsp.didOpen(uri, server.languageId, text)) }
              else
                Lsp.publishDiagnosticsFor(json, uri) match
                  case Some(ds) => Sync.defer { diags = Some(ds); () }
                  case None     => Sync.defer(())
            case _ => Sync.defer(())

        val pump =
          Scope.run {
            proc.stdout.fold(()) { (_, b) =>
              buf.append((b & 0xff).toChar)
              val (frames, rest) = Lsp.decodeFrames(buf.toString)
              if frames.nonEmpty then { buf.setLength(0); buf.append(rest) }
              Kyo.foreachDiscard(frames)(handleFrame)
            }
          }

        val work = Sync.defer(write(Lsp.initialize(1L, "file://" + root)))
          .andThen(Abort.run[Timeout](Async.timeout(8.seconds)(pump)))
          .andThen(Sync.defer { proc.unsafe.destroy(); () })
        work.map(_ => Result.succeed(diags.getOrElse(Nil)))
    }
end LspClient

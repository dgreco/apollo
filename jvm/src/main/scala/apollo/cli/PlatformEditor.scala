package apollo.cli

import kyo.*
import org.jline.reader.{EndOfFileException, LineReader, LineReaderBuilder, UserInterruptException}
import org.jline.terminal.{Terminal, TerminalBuilder}

/** JVM line editor: JLine 3/4 — its default emacs keymap already provides
  * arrow keys, Ctrl-A/E, history and kill-ring. Falls back to plain
  * `scala.io.StdIn` when stdin isn't a real console (pipes, CI), where
  * JLine's reader misbehaves on masked input.
  */
object PlatformEditor:

  def create: LineEditor < Sync =
    Sync.defer {
      if java.lang.System.console() == null then FallbackEditor
      else
        try
          val terminal = TerminalBuilder.builder().system(true).build()
          val reader   = LineReaderBuilder.builder().terminal(terminal).build()
          new JLineEditor(terminal, reader)
        catch case _: Exception => FallbackEditor
    }

  private final class JLineEditor(terminal: Terminal, reader: LineReader) extends LineEditor:

    def readLine(prompt: String): Maybe[String] < (Sync & Async) =
      Sync.defer {
        try Present(reader.readLine(prompt))
        catch
          case _: UserInterruptException => Present("") // Ctrl-C clears the line
          case _: EndOfFileException     => Absent
      }

    def readSecret(prompt: String): Maybe[String] < (Sync & Async) =
      Sync.defer {
        try Present(reader.readLine(prompt, '*'))
        catch
          case _: UserInterruptException => Present("")
          case _: EndOfFileException     => Absent
      }

    def onInterrupt(handler: () => Unit): Boolean < Sync =
      Sync.defer {
        // JLine owns the terminal's signal handling; registering through it
        // avoids fighting the reader for SIGINT ownership.
        terminal.handle(Terminal.Signal.INT, _ => handler())
        true
      }

    def isInteractive: Boolean < Sync = Sync.defer(true)

    // JLine's supported way to emit output above an active readLine (called from
    // the turn fiber while the main fiber is blocked in readLine).
    def printAbove(text: String): Unit < Sync = Sync.defer { reader.printAbove(text); () }
  end JLineEditor

  private object FallbackEditor extends LineEditor:
    def readLine(prompt: String): Maybe[String] < (Sync & Async) =
      Sync.defer {
        print(prompt)
        Maybe.fromOption(Option(scala.io.StdIn.readLine()))
      }
    def readSecret(prompt: String): Maybe[String] < (Sync & Async) = readLine(prompt)
    def onInterrupt(handler: () => Unit): Boolean < Sync =
      Sync.defer {
        try
          sun.misc.Signal.handle(new sun.misc.Signal("INT"), _ => handler())
          true
        catch case _: Throwable => false
      }
    def isInteractive: Boolean < Sync = Sync.defer(false)
    def printAbove(text: String): Unit < Sync = Sync.defer(println(text))
end PlatformEditor

package apollo.cli

import kyo.*
import scala.collection.mutable.ArrayBuffer
import scala.scalanative.posix.termios
import scala.scalanative.posix.termios.*
import scala.scalanative.posix.unistd
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

/** Scala Native line editor: a hand-rolled raw-mode editor over termios
  * (no published readline binding exists for Native). Supports arrow keys,
  * Ctrl-A/E, backspace, insert-at-cursor, in-memory history with
  * draft-restore, Ctrl-C-clears-line and Ctrl-D-EOF. Falls back to plain
  * `Console.in` reads when stdin isn't a TTY.
  *
  * SIGINT during agent turns is a documented no-op on Native (default OS
  * termination applies) — see README.
  */
object PlatformEditor:

  def create: LineEditor < Sync =
    Sync.defer {
      if isTty then RawEditor else FallbackEditor
    }

  private def isTty: Boolean =
    unistd.isatty(0) == 1

  private object FallbackEditor extends LineEditor:
    def readLine(prompt: String): Maybe[String] < (Sync & Async) =
      Sync.defer {
        print(prompt)
        Maybe.fromOption(Option(scala.io.StdIn.readLine()))
      }
    def readSecret(prompt: String): Maybe[String] < (Sync & Async) = readLine(prompt)
    def onInterrupt(handler: () => Unit): Boolean < Sync           = Sync.defer(false)
    def isInteractive: Boolean < Sync                              = Sync.defer(false)
    def printAbove(text: String): Unit < Sync                      = Sync.defer(println(text))

  private object RawEditor extends LineEditor:

    private val history = ArrayBuffer.empty[String]

    def readLine(prompt: String): Maybe[String] < (Sync & Async) =
      Sync.defer(edit(prompt, mask = false))

    def readSecret(prompt: String): Maybe[String] < (Sync & Async) =
      Sync.defer(edit(prompt, mask = true))

    def onInterrupt(handler: () => Unit): Boolean < Sync = Sync.defer(false)

    def isInteractive: Boolean < Sync = Sync.defer(true)

    // Native has no concurrent-input TUI yet; plain newline print (async_input
    // mode is JVM-only in practice — see REPL_PARITY.md).
    def printAbove(text: String): Unit < Sync = Sync.defer(println(text))

    private def edit(prompt: String, mask: Boolean): Maybe[String] =
      withRawMode {
        val buffer       = ArrayBuffer.empty[Char]
        var cursor       = 0
        var historyIdx   = history.length
        var draft        = ""
        var result: Maybe[Maybe[String]] = Absent

        def redraw(): Unit =
          val shown = if mask then "*" * buffer.length else buffer.mkString
          val back  = buffer.length - cursor
          print(s"\r\u001b[K$prompt$shown")
          if back > 0 then print(s"\u001b[${back}D")
          java.lang.System.out.flush()

        redraw()
        while result.isEmpty do
          val c = readByte()
          c match
            case -1 | 4 => // EOF / Ctrl-D
              if buffer.isEmpty then result = Present(Absent)
              else () // Ctrl-D mid-line ignored
            case 13 | 10 => // Enter
              println()
              val line = buffer.mkString
              if line.nonEmpty && !mask then history += line
              result = Present(Present(line))
            case 3 => // Ctrl-C clears the line
              buffer.clear(); cursor = 0; redraw()
            case 1 => cursor = 0; redraw()             // Ctrl-A
            case 5 => cursor = buffer.length; redraw() // Ctrl-E
            case 127 | 8 => // Backspace
              if cursor > 0 then
                buffer.remove(cursor - 1)
                cursor -= 1
                redraw()
            case 27 => // ESC sequences
              val b1 = readByte()
              if b1 == '[' || b1 == 'O' then
                readByte() match
                  case 'D' => if cursor > 0 then { cursor -= 1; redraw() }              // left
                  case 'C' => if cursor < buffer.length then { cursor += 1; redraw() }  // right
                  case 'A' => // up: history back
                    if historyIdx > 0 then
                      if historyIdx == history.length then draft = buffer.mkString
                      historyIdx -= 1
                      buffer.clear(); buffer ++= history(historyIdx); cursor = buffer.length; redraw()
                  case 'B' => // down: history forward (restoring the draft at the end)
                    if historyIdx < history.length then
                      historyIdx += 1
                      buffer.clear()
                      buffer ++= (if historyIdx == history.length then draft else history(historyIdx))
                      cursor = buffer.length
                      redraw()
                  case _ => ()
            case printable if printable >= 32 =>
              buffer.insert(cursor, printable.toChar)
              cursor += 1
              redraw()
            case _ => ()
        end while
        result.getOrElse(Absent)
      }
    end edit

    private def readByte(): Int =
      val buf = stackalloc[Byte]()
      val n   = unistd.read(0, buf, 1.toCSize)
      if n <= 0 then -1 else (buf(0) & 0xff)

    /** Disables ICANON/ECHO/ISIG for the block; always restores. Falls back
      * to a plain read when tcgetattr fails.
      */
    private def withRawMode[A](body: => A): A =
      val saved = stackalloc[termios.termios]()
      if tcgetattr(0, saved) != 0 then body
      else
        val raw = stackalloc[termios.termios]()
        if tcgetattr(0, raw) != 0 then body
        else
          raw._4 = raw._4 & ~(ICANON | ECHO | ISIG).toUInt // c_lflag
          try
            tcsetattr(0, TCSAFLUSH, raw)
            body
          finally
            tcsetattr(0, TCSAFLUSH, saved)
            ()
  end RawEditor
end PlatformEditor

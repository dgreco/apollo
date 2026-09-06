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
  * draft-restore, Ctrl-C-clears-line and Ctrl-D-EOF, plus a live slash-command
  * menu. Falls back to plain `Console.in` reads when stdin isn't a TTY.
  *
  * SIGINT during agent turns is a documented no-op on Native (default OS
  * termination applies) — see README.
  */
object PlatformEditor:

  // ANSI escape building blocks (Esc = 0x1B), kept as a named char so the
  // source has no invisible control bytes.
  private val Esc      = 27.toChar
  private val ClrBelow = s"$Esc[J"       // clear to end of screen
  private def back(n: Int) = s"$Esc[${n}D" // move cursor left n columns
  private def up(n: Int)   = s"$Esc[${n}A" // move cursor up n rows (relative, scroll-safe)
  private val Dim      = s"$Esc[2m"      // dim text
  private val Rst      = s"$Esc[0m"      // reset attributes

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

    // Shared state so printAbove (called from the turn fiber) can redraw the
    // input line the edit loop (blocked in readByte on another fiber) owns.
    private val ioLock = new AnyRef
    @volatile private var reading   = false
    @volatile private var curPrompt = ""
    @volatile private var curLine   = ""  // current visible buffer contents
    @volatile private var curBack   = 0   // columns from line end back to the cursor
    @volatile private var curMask   = false
    // Live slash-command menu (Hermes-style). On by default; APOLLO_NO_COMMAND_MENU disables.
    private val menuEnabled = !sys.env.contains("APOLLO_NO_COMMAND_MENU")

    def readLine(prompt: String): Maybe[String] < (Sync & Async) =
      Sync.defer(edit(prompt, mask = false))

    def readSecret(prompt: String): Maybe[String] < (Sync & Async) =
      Sync.defer(edit(prompt, mask = true))

    def onInterrupt(handler: () => Unit): Boolean < Sync = Sync.defer(false)

    def isInteractive: Boolean < Sync = Sync.defer(true)

    override def terminalWidth: Int < Sync = Sync.defer(queryWidth())

    /** Terminal columns via TIOCGWINSZ on stdout — tries both the macOS
      * (0x40087468) and Linux (0x5413) request numbers so it works without
      * OS detection; falls back to $COLUMNS, then 0. */
    private def queryWidth(): Int =
      try
        def viaIoctl(req: Long): Int =
          val ws = stackalloc[CStruct4[CUnsignedShort, CUnsignedShort, CUnsignedShort, CUnsignedShort]]()
          if scala.scalanative.posix.sys.ioctl.ioctl(1, req.toSize, ws.asInstanceOf[Ptr[Byte]]) == 0 then
            (!ws)._2.toInt
          else 0
        val cols = { val m = viaIoctl(0x40087468L); if m > 0 then m else viaIoctl(0x5413L) }
        if cols > 0 then cols else colsEnv
      catch case _: Throwable => colsEnv

    private def colsEnv: Int =
      Option(java.lang.System.getenv("COLUMNS")).flatMap(_.toIntOption).getOrElse(0)

    // Emit output above the live input line: clear the line (and any menu below),
    // print the text, then reprint the prompt + current buffer and restore the
    // cursor. Serialized with the edit loop's own redraw via ioLock so their
    // writes never interleave.
    def printAbove(text: String): Unit < Sync = Sync.defer {
      ioLock.synchronized {
        if reading && !curMask then
          print(s"\r$ClrBelow")
          print(text)
          print("\n")
          print(s"$curPrompt$curLine")
          if curBack > 0 then print(back(curBack))
          java.lang.System.out.flush()
        else
          println(text)
      }
      ()
    }

    private def edit(prompt: String, mask: Boolean): Maybe[String] =
      withRawMode {
        val buffer       = ArrayBuffer.empty[Char]
        var cursor       = 0
        var historyIdx   = history.length
        var draft        = ""
        var result: Maybe[Maybe[String]] = Absent

        def redraw(): Unit =
          val shown = if mask then "*" * buffer.length else buffer.mkString
          val b     = buffer.length - cursor
          curPrompt = prompt; curLine = shown; curBack = b; curMask = mask // for a concurrent printAbove
          // Live command menu: filtered matches drawn below the input line while
          // typing a slash command. The whole region (input line + menu) is
          // cleared each redraw with ClrBelow; after drawing the menu the cursor
          // returns to the input line via a relative cursor-up (scroll-safe).
          val menu =
            if menuEnabled && !mask then
              ReplCommands.formatMenu(ReplCommands.completeSlash(buffer.mkString), 6).map(_.take(78))
            else Nil
          val sb = new StringBuilder
          sb.append("\r").append(ClrBelow).append(prompt).append(shown)
          if menu.nonEmpty then
            // Draw the framed window below, then return to the input line with a
            // RELATIVE cursor-up (scroll-safe — save/restore breaks when typing
            // at the bottom of the screen because the newlines scroll it).
            sb.append("\n").append(s"$Dim  ╭─ commands ─ Tab completes · Enter runs$Rst")
            menu.foreach(l => sb.append("\n").append(s"$Dim  │$Rst").append(l))
            sb.append("\n").append(s"$Dim  ╰─$Rst")
            sb.append(up(menu.length + 2))              // header + items + footer
            sb.append("\r").append(prompt).append(shown) // reposition to input-line end
          if b > 0 then sb.append(back(b))
          print(sb.toString)
          java.lang.System.out.flush()

        reading = true
        redraw()
        while result.isEmpty do
          val c = readByte()
          c match
            case -1 | 4 => // EOF / Ctrl-D
              if buffer.isEmpty then result = Present(Absent)
              else () // Ctrl-D mid-line ignored
            case 13 | 10 => // Enter
              // Wipe any command menu below, keep the committed input line.
              val shownNow = if mask then "*" * buffer.length else buffer.mkString
              print(s"\r$ClrBelow$prompt$shownNow")
              println()
              val line = buffer.mkString
              if line.nonEmpty && !mask then history += line
              result = Present(Present(line))
            case 3 => // Ctrl-C clears the line
              buffer.clear(); cursor = 0; redraw()
            case 1 => cursor = 0; redraw()             // Ctrl-A
            case 5 => cursor = buffer.length; redraw() // Ctrl-E
            case 9 => // TAB — complete the current slash command
              ReplCommands.tabComplete(buffer.mkString) match
                case Some(done) => buffer.clear(); buffer ++= done; cursor = buffer.length; redraw()
                case None       => ()
            case 11 => // Ctrl-K — kill from the cursor to end of line
              if cursor < buffer.length then
                buffer.remove(cursor, buffer.length - cursor)
                redraw()
            case 16 => // Ctrl-P — open the command palette (all commands in the window below)
              if buffer.isEmpty then { buffer += '/'; cursor = 1 }
              redraw()
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
        reading = false
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

// SPDX-FileCopyrightText: 2026 David Greco <greco@acm.org>
//
// SPDX-License-Identifier: Apache-2.0

package apollo.cli

import kyo.*
import org.jline.keymap.KeyMap
import org.jline.reader.{Candidate, Completer, EndOfFileException, LineReader, LineReaderBuilder, ParsedLine, Reference, UserInterruptException, Widget}
import org.jline.terminal.{Terminal, TerminalBuilder}
import org.jline.utils.{AttributedString, Status}

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
          val reader   = LineReaderBuilder.builder()
                           .terminal(terminal)
                           .completer(SlashCompleter)
                           .build()
          // Auto-list candidates (no double-TAB), keep them compact, and don't
          // let TAB insert a literal tab so `/` + TAB always lists commands.
          reader.setOpt(LineReader.Option.AUTO_LIST)
          reader.setOpt(LineReader.Option.LIST_PACKED)
          reader.unsetOpt(LineReader.Option.INSERT_TAB)
          bindPalette(reader)
          new JLineEditor(terminal, reader)
        catch case _: Exception => FallbackEditor
    }

  /** Binds Ctrl-P to a command palette: on an empty line insert `/` so the full
    * command list shows, then trigger completion (JLine lists candidates via
    * AUTO_LIST). Overrides JLine's default Ctrl-P (previous-history). Best-effort
    * — a binding failure leaves the editor otherwise fully working. */
  private def bindPalette(reader: LineReader): Unit =
    try
      reader.getWidgets().put("apollo-palette", new Widget:
        def apply(): Boolean =
          val buf = reader.getBuffer()
          if buf.length() == 0 then buf.write("/")
          reader.callWidget(LineReader.COMPLETE_WORD)
          true
      )
      reader.getKeyMaps().get(LineReader.MAIN)
        .bind(new Reference("apollo-palette"), KeyMap.ctrl('P'))
    catch case _: Throwable => ()

  /** Completes the REPL slash commands from the shared catalog. Offered only
    * while the line is a bare command token (starts with `/`, no space yet);
    * JLine filters the candidates against what's typed, narrowing per keystroke
    * of TAB. Each candidate shows its category group and one-line summary. */
  private object SlashCompleter extends Completer:
    def complete(reader: LineReader, line: ParsedLine, candidates: java.util.List[Candidate]): Unit =
      val buf = line.line
      if buf.startsWith("/") && !buf.trim.contains(' ') then
        ReplCommands.commandCatalog.foreach { c =>
          c.names.foreach { n =>
            candidates.add(new Candidate(
              "/" + n,           // value inserted
              "/" + n,           // display
              c.category,        // group header
              c.summary,         // description
              null, null, true))
          }
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

    // Stream through JLine's own writer so it stays consistent with the pinned
    // Status line (which the ~200ms ticker's update() repaints at the bottom).
    override def emit(text: String): Unit < Sync = Sync.defer {
      val w = terminal.writer(); w.write(text); w.flush()
    }

    override def terminalWidth: Int < Sync = Sync.defer {
      val w = terminal.getWidth; if w > 0 then w else 0
    }

    override def terminalHeight: Int < Sync = Sync.defer {
      val h = terminal.getHeight; if h > 0 then h else 0
    }

    // --- pinned bottom status bar (JLine reserves the bottom line) ----------
    private val barOff = sys.env.contains("APOLLO_NO_STATUS_BAR")

    override def supportsBottomBar: Boolean < Sync = Sync.defer {
      !barOff && (try Status.getStatus(terminal, true) != null catch case _: Throwable => false)
    }

    override def enableBottomBar(): Unit < Sync = Sync.defer {
      if !barOff then
        try
          val st = Status.getStatus(terminal, true)
          if st != null then st.setBorder(false)
        catch case _: Throwable => ()
    }

    override def bottomBar(text: String): Unit < Sync = Sync.defer {
      if !barOff then
        try
          val st = Status.getStatus(terminal, true)
          if st != null then
            // fromAnsi parses our truecolor escapes into JLine's styled string.
            st.update(java.util.Collections.singletonList(AttributedString.fromAnsi(text)))
        catch case _: Throwable => ()
    }

    override def disableBottomBar(): Unit < Sync = Sync.defer {
      try
        val st = Status.getStatus(terminal, false)
        if st != null then st.reset()
      catch case _: Throwable => ()
    }
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

// SPDX-FileCopyrightText: 2026 David Greco <greco@acm.org>
//
// SPDX-License-Identifier: Apache-2.0

package apollo.cli

import kyo.*

/** Platform line-editing abstraction. The JVM binds this to JLine (arrow
  * keys, history, emacs bindings out of the box); Scala Native to a
  * hand-rolled raw-mode termios editor. Both fall back to plain stdin reads
  * when no real TTY is attached (pipes, CI).
  */
trait LineEditor:
  /** Reads one line; Absent = EOF (Ctrl-D / closed stdin). */
  def readLine(prompt: String): Maybe[String] < (Sync & Async)

  /** Reads without echo (API keys). */
  def readSecret(prompt: String): Maybe[String] < (Sync & Async)

  /** Installs a SIGINT handler for the duration of agent turns. Returns
    * true when the platform supports it (JVM yes; Native is a documented
    * no-op that leaves default Ctrl-C termination in place).
    */
  def onInterrupt(handler: () => Unit): Boolean < Sync

  def isInteractive: Boolean < Sync

  /** Prints a line of output ABOVE the current input line without corrupting
    * it — the primitive for concurrent-input mode (a turn streams output while
    * the user types). JVM binds this to JLine's `LineReader.printAbove`; other
    * editors fall back to a plain `println`.
    */
  def printAbove(text: String): Unit < Sync

  /** Writes raw text (possibly a partial line) at the current cursor, serialized
    * with any pinned-bar repaints so a concurrent ticker can't corrupt it. Used
    * to stream turn output while a bottom bar is pinned. Non-pinning editors
    * just print — identical to the previous direct `print`. */
  def emit(text: String): Unit < Sync =
    Sync.defer { print(text); java.lang.System.out.flush() }

  /** Terminal width in columns, or 0 when unknown (non-TTY / undetectable).
    * Used to size the full-width status bar. */
  def terminalWidth: Int < Sync = Sync.defer(0)

  /** Terminal height in rows, or 0 when unknown. */
  def terminalHeight: Int < Sync = Sync.defer(0)

  // --- pinned bottom status bar -------------------------------------------
  // A status line reserved at the very bottom of the screen: output scrolls
  // ABOVE it and it stays fixed (JLine's Status on the JVM; a DECSTBM scroll
  // region on Native). Default no-ops for editors that can't pin (fallbacks).

  /** True when this editor can pin a bottom bar (interactive TTY). */
  def supportsBottomBar: Boolean < Sync = Sync.defer(false)

  /** Reserve the bottom row and start pinning; call before printing content. */
  def enableBottomBar(): Unit < Sync = Sync.defer(())

  /** Set/repaint the pinned bar's text (already colored + width-sized). */
  def bottomBar(text: String): Unit < Sync = Sync.defer(())

  /** Release the reserved row and clear the bar (on exit). */
  def disableBottomBar(): Unit < Sync = Sync.defer(())

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

  /** Terminal width in columns, or 0 when unknown (non-TTY / undetectable).
    * Used to size the full-width status bar. */
  def terminalWidth: Int < Sync = Sync.defer(0)

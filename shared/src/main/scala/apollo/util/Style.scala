// SPDX-FileCopyrightText: 2026 David Greco <greco@acm.org>
//
// SPDX-License-Identifier: Apache-2.0

package apollo.util

/** ANSI styling helpers (degrade to plain text when `NO_COLOR` is set).
  *
  * Pure `String => String`: every surface that writes to a terminal — the REPL,
  * the setup wizard, the gateway's startup errors — shares these, so none of
  * them has to depend on the CLI for a colour code.
  */
object Style:
  private val esc = 27.toChar
  private def colorEnabled = !sys.env.contains("NO_COLOR")
  private def wrap(code: String, s: String) = if colorEnabled then s"$esc[${code}m$s$esc[0m" else s
  def dim(s: String)    = wrap("2", s)
  def bold(s: String)   = wrap("1", s)
  def gold(s: String)   = wrap("38;5;178", s)
  def red(s: String)    = wrap("31", s)
  def green(s: String)  = wrap("32", s)

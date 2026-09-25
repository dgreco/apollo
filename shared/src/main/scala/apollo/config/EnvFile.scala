// SPDX-FileCopyrightText: 2026 David Greco <greco@acm.org>
//
// SPDX-License-Identifier: Apache-2.0

package apollo.config

/** Parser for dotenv-style files (`<home>/.env`).
  *
  * Supports the common dotenv dialect the upstream harness uses: `KEY=VALUE` lines, blank
  * lines, `#` comments, an optional `export ` prefix, and single/double quoted
  * values (double quotes process `\n`, `\t`, `\"` and `\\` escapes; single
  * quotes are literal).
  */
object EnvFile:

  def parse(content: String): Map[String, String] =
    content.linesIterator
      .map(_.trim)
      .filter(line => line.nonEmpty && !line.startsWith("#"))
      .flatMap(parseLine)
      .toMap

  private def parseLine(line: String): Option[(String, String)] =
    val withoutExport = if line.startsWith("export ") then line.drop(7).trim else line
    withoutExport.indexOf('=') match
      case -1 => None
      case i =>
        val key = withoutExport.take(i).trim
        if key.isEmpty || !key.forall(c => c.isLetterOrDigit || c == '_') then None
        else Some(key -> unquote(withoutExport.drop(i + 1).trim))

  private def unquote(raw: String): String =
    if raw.length >= 2 && raw.head == '"' && raw.last == '"' then
      unescape(raw.substring(1, raw.length - 1))
    else if raw.length >= 2 && raw.head == '\'' && raw.last == '\'' then
      raw.substring(1, raw.length - 1)
    else
      // Unquoted values end at an inline comment (space + #).
      val commentIdx = raw.indexOf(" #")
      (if commentIdx >= 0 then raw.take(commentIdx) else raw).trim

  private def unescape(s: String): String =
    val sb = new StringBuilder(s.length)
    var i  = 0
    while i < s.length do
      val c = s.charAt(i)
      if c == '\\' && i + 1 < s.length then
        s.charAt(i + 1) match
          case 'n'   => sb.append('\n'); i += 2
          case 't'   => sb.append('\t'); i += 2
          case 'r'   => sb.append('\r'); i += 2
          case '"'   => sb.append('"'); i += 2
          case '\\'  => sb.append('\\'); i += 2
          case other => sb.append(c).append(other); i += 2
      else
        sb.append(c)
        i += 1
    sb.result()

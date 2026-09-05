package apollo.mcp

import kyo.*

/** Pure, line-based edits of the `mcp_servers:` section of `config.yaml`,
  * backing `apollo mcp add` / `apollo mcp remove`. Line-based (not a YAML
  * round-trip) so the rest of the file — comments, ordering, unrelated
  * sections — is preserved, and Native-safe (no regex lookahead).
  */
object McpConfigEdit:

  /** One server's config as CLI-supplied fields. */
  final case class ServerSpec(
      command: Maybe[String] = Absent,
      args: List[String] = Nil,
      url: Maybe[String] = Absent,
      transport: Maybe[String] = Absent,
      auth: Maybe[String] = Absent,
      env: List[(String, String)] = Nil,
      headers: List[(String, String)] = Nil
  ):
    def isStdio: Boolean = url.isEmpty

  private def q(s: String): String =
    "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

  private def yamlList(items: List[String]): String = items.map(q).mkString("[", ", ", "]")

  /** Renders the indented YAML block for `<name>:` (2-space server indent). */
  def renderEntry(name: String, spec: ServerSpec): List[String] =
    val b = List.newBuilder[String]
    b += s"  $name:"
    spec.command.foreach(c => b += s"    command: ${q(c)}")
    if spec.args.nonEmpty then b += s"    args: ${yamlList(spec.args)}"
    spec.url.foreach(u => b += s"    url: ${q(u)}")
    spec.transport.foreach(t => b += s"    transport: $t")
    spec.auth.foreach(a => b += s"    auth: $a")
    if spec.env.nonEmpty then
      b += "    env:"
      spec.env.foreach((k, v) => b += s"      $k: ${q(v)}")
    if spec.headers.nonEmpty then
      b += "    headers:"
      spec.headers.foreach((k, v) => b += s"      $k: ${q(v)}")
    b.result()

  /** Adds (or replaces) `name` under `mcp_servers:`. */
  def addServer(existing: Maybe[String], name: String, spec: ServerSpec): String =
    val entry = renderEntry(name, spec)
    existing match
      case Absent =>
        ("# apollo configuration" :: "mcp_servers:" :: entry).mkString("\n") + "\n"
      case Present(text) =>
        val lines = removeServerLines(text.split("\n", -1).toVector, name)
        val header = lines.indexWhere(l => l == "mcp_servers:" || l.startsWith("mcp_servers:"))
        if header < 0 then
          (lines.mkString("\n").stripSuffix("\n") + "\n\nmcp_servers:\n" + entry.mkString("\n") + "\n")
        else
          // Insert the entry immediately after the `mcp_servers:` header line.
          (lines.take(header + 1) ++ entry ++ lines.drop(header + 1)).mkString("\n")

  /** Removes `name` from `mcp_servers:`; a no-op (unchanged text) if absent. */
  def removeServer(existing: Maybe[String], name: String): String =
    existing match
      case Absent        => ""
      case Present(text) => removeServerLines(text.split("\n", -1).toVector, name).mkString("\n")

  def hasServer(existing: Maybe[String], name: String): Boolean =
    existing match
      case Absent        => false
      case Present(text) => serverStart(text.split("\n", -1).toVector, name) >= 0

  // --- line surgery -------------------------------------------------------

  /** Index of the `  <name>:` line under `mcp_servers:`, or -1. A server key
    * is indented exactly two spaces; its value (nested block) is indented
    * more.
    */
  private def serverStart(lines: Vector[String], name: String): Int =
    val header = lines.indexWhere(l => l == "mcp_servers:" || l.startsWith("mcp_servers:"))
    if header < 0 then -1
    else
      var i = header + 1
      var found = -1
      while i < lines.length && found < 0 do
        val l = lines(i)
        if l.nonEmpty && !l.head.isWhitespace then i = lines.length // left the mcp_servers block
        else if isServerKey(l, name) then found = i
        else i += 1
      found

  private def isServerKey(line: String, name: String): Boolean =
    val indent = line.takeWhile(_ == ' ')
    indent.length == 2 && {
      val rest = line.drop(2)
      rest == s"$name:" || rest.startsWith(s"$name:")
    }

  /** Returns `lines` with the `<name>:` server block removed (its key line
    * through all deeper-indented lines under it).
    */
  private def removeServerLines(lines: Vector[String], name: String): Vector[String] =
    val start = serverStart(lines, name)
    if start < 0 then lines
    else
      // Block ends at the next line indented ≤2 spaces that is non-blank
      // (next server key or a top-level line), or end of file.
      var end = start + 1
      while end < lines.length &&
        (lines(end).isEmpty || lines(end).takeWhile(_ == ' ').length > 2)
      do end += 1
      lines.take(start) ++ lines.drop(end)
end McpConfigEdit

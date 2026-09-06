package apollo.cli

/** The startup welcome screen — an ASCII-art title and a framed two-column panel
  * (an apollo mark + session info on the left, available tools and skills grouped
  * by category on the right), modeled on the Hermes agent's banner.
  *
  * All layout is pure and width-computed on ANSI-stripped text so colored cells
  * still align. `Repl` supplies the grouped data; interactive rendering is the
  * caller's concern.
  */
object Banner:

  // Big block-letter "APOLLO" (5 rows, glyphs A P O L assembled by hand).
  private val title: List[String] = List(
    " ██   ███   ████  █     █     ████ ",
    "█  █  █  █  █  █  █     █     █  █ ",
    "████  ███   █  █  █     █     █  █ ",
    "█  █  █     █  █  █     █     █  █ ",
    "█  █  █     ████  ████  ████  ████ "
  )

  // A small sun mark for the left column (apollo = sun god).
  private val mark: List[String] = List(
    "    \\ | /    ",
    "  '  .-.  '  ",
    "-- (  ☀  ) --",
    "  .  '-'  .  ",
    "    / | \\    "
  )

  private val ansi = "\\[[0-9;]*m".r
  /** Visible width of a string, ignoring ANSI color escapes. */
  def plainWidth(s: String): Int = ansi.replaceAllIn(s, "").length

  private def padTo(s: String, w: Int): String =
    val pad = w - plainWidth(s)
    if pad > 0 then s + (" " * pad) else s

  /** Greedily join items to fit `budget` visible columns, summarizing the rest
    * as "+N more". */
  def fitItems(items: List[String], budget: Int): String =
    val out = scala.collection.mutable.ListBuffer[String]()
    var len = 0
    var i   = 0
    var stop = false
    while i < items.length && !stop do
      val add = (if out.isEmpty then 0 else 2) + items(i).length
      if len + add <= budget then { out += items(i); len += add; i += 1 }
      else stop = true
    val remaining = items.length - out.length
    val base = out.mkString(", ")
    if remaining <= 0 then base
    else if base.isEmpty then s"+$remaining more"
    else s"$base, +$remaining more"

  /** "  label: a, b, c, +N more", fit to `width` visible columns. */
  def groupLine(label: String, items: List[String], width: Int): String =
    s"  $label: " + fitItems(items, math.max(4, width - (label.length + 4)))

  /** Place two blocks side by side; shorter block padded with blank rows. */
  def twoColumn(left: List[String], right: List[String], gap: Int): List[String] =
    val leftW = left.map(plainWidth).maxOption.getOrElse(0)
    val rows  = math.max(left.length, right.length)
    (0 until rows).toList.map { i =>
      val l = if i < left.length then left(i) else ""
      val r = if i < right.length then right(i) else ""
      padTo(l, leftW) + (" " * gap) + r
    }

  /** Wrap content lines in a rounded box sized to the widest line. */
  def box(lines: List[String]): List[String] =
    val inner = lines.map(plainWidth).maxOption.getOrElse(0)
    val top   = "╭" + ("─" * (inner + 2)) + "╮"
    val bot   = "╰" + ("─" * (inner + 2)) + "╯"
    top :: lines.map(l => "│ " + padTo(l, inner) + " │") ::: List(bot)

  /** Full welcome screen. `tools`/`skills` are (group, members) already sorted;
    * `moreToolsets` is the count elided from the tools listing. */
  def render(
      version: String, displayName: String, model: String, provider: String,
      cwd: String, session: String,
      tools: List[(String, List[String])],
      skills: List[(String, List[String])],
      toolCount: Int, skillCount: Int, moreToolsets: Int,
      colored: Boolean = true
  ): String =
    def gold(s: String) = if colored then Style.gold(s) else s
    def dim(s: String)  = if colored then Style.dim(s) else s
    val rightW = 66

    val toolLines =
      gold("Available Tools") ::
        tools.map((ts, ns) => groupLine(ts, ns, rightW)) :::
        (if moreToolsets > 0 then List(dim(s"  (and $moreToolsets more toolsets…)")) else Nil)
    val skillLines =
      gold("Available Skills") :: skills.map((c, ns) => groupLine(c, ns, rightW))
    val right = toolLines ::: List("") ::: skillLines ::: List(
      "", dim(s"$toolCount tools · $skillCount skills · /help for commands"))

    val left = mark ::: List(
      "", gold(s"$displayName"), s"$model ($provider)", dim(cwd), dim(s"session $session"))

    val header = gold(s"apollo v$version") + s" · $displayName $model"
    val body   = box(twoColumn(left, right, gap = 3))

    (title.map(gold) ::: List("", header, "") ::: body ::: List(
      "", s"Welcome to apollo! Type your message or /help for commands."
    )).mkString("\n")
end Banner

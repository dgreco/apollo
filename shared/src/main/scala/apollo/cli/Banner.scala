package apollo.cli

/** The startup welcome screen — a gradient figlet title and a framed two-column
  * panel (an apollo sun + session info on the left, tools and skills grouped by
  * category on the right), matching the Hermes agent's banner layout: the
  * version label sits on the panel's top border, section headers are amber,
  * group labels dim gold, item names cream, and the border bronze.
  *
  * All layout is width-computed on ANSI-stripped text so colored cells align.
  */
object Banner:

  // --- palette (24-bit truecolor, matching Hermes's default skin) ----------
  private def esc(code: String, s: String, on: Boolean): String =
    if on then s"\u001b[${code}m$s\u001b[0m" else s
  // gold #FFD700, amber #FFBF00, bronze #CD7F32, dark-gold #B8860B, cream #FFF8DC, grey #8B8682
  private def cTitle(s: String, on: Boolean)  = esc("1;38;2;255;215;0", s, on)
  private def cGold(s: String, on: Boolean)   = esc("1;38;2;255;215;0", s, on)
  private def cAmber(s: String, on: Boolean)  = esc("38;2;255;191;0", s, on)
  private def cBronze(s: String, on: Boolean) = esc("38;2;205;127;50", s, on)
  private def cDim(s: String, on: Boolean)    = esc("38;2;184;134;11", s, on)
  private def cText(s: String, on: Boolean)   = esc("38;2;255;248;220", s, on)
  private def cGrey(s: String, on: Boolean)   = esc("38;2;139;134;130", s, on)

  // Big "APOLLO" in the ANSI-Shadow figlet font, 6 rows, gold→amber→bronze.
  private val logoRows: List[String] = List(
    " █████╗ ██████╗  ██████╗ ██╗     ██╗      ██████╗ ",
    "██╔══██╗██╔══██╗██╔═══██╗██║     ██║     ██╔═══██╗",
    "███████║██████╔╝██║   ██║██║     ██║     ██║   ██║",
    "██╔══██║██╔═══╝ ██║   ██║██║     ██║     ██║   ██║",
    "██║  ██║██║     ╚██████╔╝███████╗███████╗╚██████╔╝",
    "╚═╝  ╚═╝╚═╝      ╚═════╝ ╚══════╝╚══════╝ ╚═════╝ "
  )
  private def logo(on: Boolean): List[String] =
    logoRows.zipWithIndex.map { (row, i) =>
      i match
        case 0 | 1 => cGold(row, on)
        case 2 | 3 => cAmber(row, on)
        case _     => cBronze(row, on)
    }

  // A rayed sun for the left column, 9 rows, radial gradient (bronze→gold→bronze).
  private val sunRows: List[String] = List(
    "       \\   '   /       ",
    "    '.   \\  |  /   .'    ",
    "  '-.  '.  .-.  .'  .-'  ",
    "     '.  ( ☀ )  .'      ",
    "  ─ ─ ─(  ☀☀☀  )─ ─ ─   ",
    "     .'  ( ☀ )  '.      ",
    "  .-'  .'  '-'  '.  '-.  ",
    "    .'   /  |  \\   '.    ",
    "       /   .   \\       "
  )
  private def sun(on: Boolean): List[String] =
    sunRows.zipWithIndex.map { (row, i) =>
      i match
        case 0 | 8       => cBronze(row, on)
        case 1 | 7       => cDim(row, on)
        case 2 | 6       => cAmber(row, on)
        case _           => cGold(row, on)
    }

  private val ansi = "\u001b\\[[0-9;]*m".r
  /** Visible width of a string, ignoring ANSI color escapes. */
  def plainWidth(s: String): Int = ansi.replaceAllIn(s, "").length

  private def padTo(s: String, w: Int): String =
    val pad = w - plainWidth(s); if pad > 0 then s + (" " * pad) else s
  private def center(s: String, w: Int): String =
    val pad = w - plainWidth(s)
    if pad <= 0 then s else (" " * (pad / 2)) + s + (" " * (pad - pad / 2))

  /** Greedily join items to fit `budget` visible columns, summarizing the rest. */
  def fitItems(items: List[String], budget: Int): String =
    val out = scala.collection.mutable.ListBuffer[String]()
    var len = 0; var i = 0; var stop = false
    while i < items.length && !stop do
      val add = (if out.isEmpty then 0 else 2) + items(i).length
      if len + add <= budget then { out += items(i); len += add; i += 1 } else stop = true
    val remaining = items.length - out.length
    val base = out.mkString(", ")
    if remaining <= 0 then base
    else if base.isEmpty then s"+$remaining more" else s"$base, +$remaining more"

  /** "label: a, b, c" — dim label, cream item names, fit to `width` columns. */
  private def groupLine(label: String, items: List[String], width: Int, on: Boolean): String =
    val names = fitItems(items, math.max(4, width - (label.length + 4)))
    cDim(s"  $label:", on) + " " + cText(names, on)

  /** Place two blocks side by side; left block centered in its column. */
  private def twoColumn(left: List[String], right: List[String], gap: Int): List[String] =
    val leftW = left.map(plainWidth).maxOption.getOrElse(0)
    val rows  = math.max(left.length, right.length)
    (0 until rows).toList.map { i =>
      val l = if i < left.length then left(i) else ""
      val r = if i < right.length then right(i) else ""
      center(l, leftW) + (" " * gap) + r
    }

  /** Rounded box with `title` centered on the top border (bronze border, gold title). */
  private def boxTitled(lines: List[String], title: String, on: Boolean): List[String] =
    val inner = lines.map(plainWidth).maxOption.getOrElse(0)
    val run   = inner + 2 // dashes between the corners
    val t     = " " + title + " "
    val tw    = t.length
    val top =
      if tw >= run then cBronze("╭", on) + cTitle(t, on) + cBronze("╮", on)
      else
        val leftD = (run - tw) / 2; val rightD = run - tw - leftD
        cBronze("╭" + "─" * leftD, on) + cTitle(t, on) + cBronze("─" * rightD + "╮", on)
    val bot  = cBronze("╰" + "─" * run + "╯", on)
    val side = cBronze("│", on)
    top :: lines.map(l => s"$side " + padTo(l, inner) + s" $side") ::: List(bot)

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
    val on = colored
    val rightW = 66

    val toolLines =
      cAmber("Available Tools", on) ::
        tools.map((ts, ns) => groupLine(ts, ns, rightW, on)) :::
        (if moreToolsets > 0 then List(cDim(s"  (and $moreToolsets more toolsets…)", on)) else Nil)
    val skillLines =
      cAmber("Available Skills", on) :: skills.map((c, ns) => groupLine(c, ns, rightW, on))
    val right = toolLines ::: List("") ::: skillLines ::: List(
      "", cDim(s"$toolCount tools · $skillCount skills · /help for commands", on))

    val modelShort = model.split("/").last
    val left = sun(on) ::: List(
      "",
      cAmber(modelShort, on) + cDim(s" · $displayName", on),
      cDim(cwd, on),
      cGrey(s"Session: $session", on))

    val titleLabel = s"apollo v$version · $modelShort"
    val body = boxTitled(twoColumn(left, right, gap = 2), titleLabel, on)

    (logo(on) ::: List("") ::: body ::: List(
      "",
      s"Welcome to apollo! Type your message or /help for commands.",
      cDim("· Tip: apollo reads ~/.apollo (Hermes-compatible) — config.yaml / .env carry over.", on)
    )).mkString("\n")
end Banner

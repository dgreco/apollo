package apollo

import apollo.cli.Banner

class BannerSuite extends munit.FunSuite:

  test("plainWidth ignores ANSI escapes") {
    assertEquals(Banner.plainWidth("abc"), 3)
    assertEquals(Banner.plainWidth("[38;5;178mabc[0m"), 3)
    assertEquals(Banner.plainWidth("[2mx[0my"), 2)
  }

  test("fitItems keeps what fits and summarizes the rest") {
    assertEquals(Banner.fitItems(List("aa", "bb", "cc"), 100), "aa, bb, cc")
    // budget only fits the first item → "+N more"
    val out = Banner.fitItems(List("aaaa", "bbbb", "cccc"), 6)
    assert(out.startsWith("aaaa") && out.contains("+2 more"), out)
    assertEquals(Banner.fitItems(Nil, 10), "")
  }

  test("groupLine formats label + fitted items") {
    val l = Banner.groupLine("file", List("read_file", "write_file"), 100)
    assertEquals(l, "  file: read_file, write_file")
  }

  test("twoColumn aligns and pads the shorter block") {
    val left  = List("a", "bbb")
    val right = List("1", "2", "3")
    val out   = Banner.twoColumn(left, right, gap = 2)
    assertEquals(out.length, 3)                 // padded to the taller block
    // left column padded to width 3 ("bbb"), gap 2
    assertEquals(out(0), "a  " + "  " + "1")
    assertEquals(out(2), "   " + "  " + "3")    // left row missing → blanks
  }

  test("box frames content to the widest line, all rows equal width") {
    val b = Banner.box(List("hi", "longer line"))
    assertEquals(b.head.head, '╭')
    assertEquals(b.last.head, '╰')
    val widths = b.map(Banner.plainWidth).toSet
    assertEquals(widths.size, 1)                // every row same visible width
    assert(b(1).startsWith("│ ") && b(1).endsWith(" │"), b(1))
  }

  test("render includes title, tools, skills, counts, welcome") {
    val out = Banner.render(
      version = "0.1.0", displayName = "mock", model = "m", provider = "custom",
      cwd = "/w/apollo", session = "sess1",
      tools = List("file" -> List("read_file", "write_file"), "web" -> List("web_search")),
      skills = List("research" -> List("arxiv", "rss-feeds")),
      toolCount = 3, skillCount = 2, moreToolsets = 4, colored = false)
    assert(out.contains("Available Tools"), out)
    assert(out.contains("file: read_file, write_file"), out)
    assert(out.contains("Available Skills"), out)
    assert(out.contains("research: arxiv, rss-feeds"), out)
    assert(out.contains("(and 4 more toolsets"), out)
    assert(out.contains("3 tools · 2 skills · /help for commands"), out)
    assert(out.contains("Welcome to apollo!"), out)
    assert(out.contains("session sess1"), out)
    // box is present
    assert(out.contains("╭") && out.contains("╰"), out)
  }
end BannerSuite

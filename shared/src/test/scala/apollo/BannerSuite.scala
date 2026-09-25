// SPDX-FileCopyrightText: 2026 David Greco <greco@acm.org>
//
// SPDX-License-Identifier: Apache-2.0

package apollo.cli

class BannerSuite extends munit.FunSuite:

  private val tools = List(
    "browser" -> List("browser"),
    "code_execution" -> List("execute_code"),
    "computer_use" -> List("computer_use"),
    "file" -> List("patch", "read_file", "search_files", "write_file"),
    "image_gen" -> List("image_generate"))
  private val skills = List(
    "apple" -> List("apple-mail", "apple-notes", "imessage"),
    "creative" -> List("ascii-art", "excalidraw", "humanizer"),
    "research" -> List("arxiv", "rss-feeds"))

  test("layout: logo, titled box, dim labels + cream names, session line") {
    val out = Banner.render(
      "0.1.0", "Nous Research", "deepseek/deepseek-v4-flash", "nous",
      "/Users/dgreco/Workspace/apollo", "20260906_191149_00cf94",
      tools, skills, toolCount = 19, skillCount = 87, moreToolsets = 8, colored = false)
    // logo present (figlet block letters)
    assert(out.contains("█████╗"), out)
    // version label centred on the top border of the panel
    assert(out.contains("╭") && out.contains("apollo v0.1.0"), out)
    assert(out.split("\n").exists(l => l.startsWith("╭") && l.contains("apollo v0.1.0")), "version on top border")
    // section headers + a group line + summary
    assert(out.contains("Available Tools") && out.contains("Available Skills"), out)
    assert(out.contains("file:") && out.contains("write_file"), out)
    assert(out.contains("(and 8 more toolsets…)"), out)
    assert(out.contains("19 tools · 87 skills · /help for commands"), out)
    // left column: model short + provider, cwd, Session:
    assert(out.contains("deepseek-v4-flash · Nous Research"), out)
    assert(out.contains("Session: 20260906_191149_00cf94"), out)
    assert(out.contains("Welcome to apollo!"), out)
    // print it (colored) so the layout is visible in the test log
    println("\n" + Banner.render(
      "0.1.0", "Nous Research", "deepseek/deepseek-v4-flash", "nous",
      "/Users/dgreco/Workspace/apollo", "20260906_191149_00cf94",
      tools, skills, toolCount = 19, skillCount = 87, moreToolsets = 8, colored = true))
  }

  test("box rows are all equal visible width (alignment)") {
    val out = Banner.render(
      "0.1.0", "Nous Research", "m", "nous", "/tmp", "s",
      tools, skills, 19, 87, 8, colored = true)
    val boxRows = out.split("\n").filter(l => l.contains("│") || l.startsWith("╭") || l.startsWith("╰"))
    val widths = boxRows.map(Banner.plainWidth).distinct
    assertEquals(widths.length, 1, s"box rows differ in width: ${widths.mkString(",")}")
  }
end BannerSuite

// SPDX-FileCopyrightText: 2026 David Greco <greco@acm.org>
//
// SPDX-License-Identifier: Apache-2.0

package apollo.cli

import apollo.config.{ApolloConfig, ApolloPaths, EnvChain, Yaml}
import apollo.mcp.{McpConfig, McpConfigEdit}
import kyo.*

/** Line-based `mcp_servers` add/remove edits: correctness, idempotent
  * replace, comment/section preservation, and round-trip parseability.
  */
class McpConfigEditSuite extends munit.FunSuite:
  import McpConfigEdit.*

  private def stdio(cmd: String, args: List[String] = Nil) =
    ServerSpec(command = Present(cmd), args = args)

  /** The edited text must still parse and expose the server via McpConfig. */
  private def parsesWith(text: String, name: String): Boolean =
    Yaml.parse(text) match
      case Result.Success(node) =>
        val cfg = ApolloConfig(Present(node), EnvChain(Map.empty),
          ApolloPaths(java.nio.file.Paths.get("/tmp")))
        McpConfig.load(cfg, java.nio.file.Paths.get("/tmp"))._1.exists(_.name == name)
      case _ => false

  test("add into a file with no mcp_servers section") {
    val out = addServer(Absent, "time", stdio("uvx", List("mcp-server-time")))
    assert(out.contains("mcp_servers:"), out)
    assert(out.contains("  time:"), out)
    assert(out.contains("""command: "uvx""""), out)
    assert(out.contains("""args: ["mcp-server-time"]"""), out)
    assert(parsesWith(out, "time"), out)
  }

  test("add appends into an existing mcp_servers section, preserving other servers + comments") {
    val existing = Present(
      """# my config
        |model: {provider: openrouter}
        |mcp_servers:
        |  existing:
        |    command: foo
        |# trailing comment
        |""".stripMargin)
    val out = addServer(existing, "added", stdio("bar"))
    assert(out.contains("  existing:"), out)      // old server kept
    assert(out.contains("  added:"), out)         // new server present
    assert(out.contains("# trailing comment"), out) // comments preserved
    assert(out.contains("model: {provider: openrouter}"), out)
    assert(parsesWith(out, "existing") && parsesWith(out, "added"), out)
  }

  test("add replaces a server of the same name (idempotent)") {
    val existing = Present(
      """mcp_servers:
        |  s:
        |    command: old
        |    args: ["a"]
        |""".stripMargin)
    val out = addServer(existing, "s", stdio("new"))
    assert(out.contains("""command: "new""""), out)
    assert(!out.contains("command: old"), out)
    assert(!out.contains("""args: ["a"]"""), out)
    // Exactly one `  s:` remains.
    assertEquals(out.split("\n").count(_ == "  s:"), 1, out)
  }

  test("http server with url, headers, transport, auth") {
    val spec = ServerSpec(url = Present("https://mcp.example.com/mcp"),
      transport = Present("sse"), auth = Present("oauth"),
      headers = List("Authorization" -> "Bearer x"))
    val out = addServer(Absent, "remote", spec)
    assert(out.contains("""url: "https://mcp.example.com/mcp""""), out)
    assert(out.contains("transport: sse"), out)
    assert(out.contains("auth: oauth"), out)
    assert(out.contains("    headers:"), out)
    assert(out.contains("""Authorization: "Bearer x""""), out)
    assert(parsesWith(out, "remote"), out)
  }

  test("remove deletes only the named server and its nested block") {
    val existing = Present(
      """mcp_servers:
        |  keep:
        |    command: k
        |  drop:
        |    command: d
        |    env:
        |      X: "1"
        |  keep2:
        |    command: k2
        |""".stripMargin)
    val out = removeServer(existing, "drop")
    assert(out.contains("  keep:") && out.contains("  keep2:"), out)
    assert(!out.contains("  drop:"), out)
    assert(!out.contains("""X: "1""""), out) // nested lines gone too
    assert(parsesWith(out, "keep") && parsesWith(out, "keep2"), out)
  }

  test("remove of a missing server leaves the text unchanged; hasServer reflects state") {
    val existing = Present("mcp_servers:\n  a:\n    command: x\n")
    assertEquals(removeServer(existing, "nope"), existing.get.split("\n", -1).mkString("\n"))
    assert(hasServer(existing, "a"))
    assert(!hasServer(existing, "nope"))
  }

  test("flag parser: stdio with args/env, http with headers, and errors") {
    def parseAddFlags(a: List[String]) = Commands.parseAddFlags(a)
    parseAddFlags(List("--command", "uvx", "--arg", "srv", "--env", "K=V")) match
      case Right(s) =>
        assertEquals(s.command, Present("uvx"))
        assertEquals(s.args, List("srv"))
        assertEquals(s.env, List("K" -> "V"))
      case Left(e) => fail(e)
    parseAddFlags(List("--url", "https://x", "--header", "A=b c")) match
      case Right(s) =>
        assertEquals(s.url, Present("https://x"))
        assertEquals(s.headers, List("A" -> "b c"))
      case Left(e) => fail(e)
    assert(parseAddFlags(List("--env", "noequals")).isLeft)
    assert(parseAddFlags(List("--bogus", "x")).isLeft)
  }
end McpConfigEditSuite

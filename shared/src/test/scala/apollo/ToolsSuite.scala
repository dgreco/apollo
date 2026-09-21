package apollo.tools

import kyo.Result

class PatchSuite extends munit.FunSuite:

  test("exact single replacement") {
    val r = FileTools.applyPatch("a\nb\nc\n", "b", "B", replaceAll = false)
    assertEquals(r, Result.succeed("a\nB\nc\n"))
  }

  test("ambiguous match requires replace_all") {
    val r = FileTools.applyPatch("x x", "x", "y", replaceAll = false)
    assert(r.isFailure)
    assertEquals(FileTools.applyPatch("x x", "x", "y", replaceAll = true), Result.succeed("y y"))
  }

  test("whitespace-tolerant fallback matches trimmed lines uniquely") {
    val content = "def foo():\n    return 1\n\ndef bar():\n    return 2\n"
    // The model supplies different indentation than the file has.
    val r = FileTools.applyPatch(content, "def foo():\n  return 1", "def foo():\n    return 42", false)
    assert(r.isSuccess, r.toString)
    assert(r.getOrElse("").contains("return 42"))
    assert(r.getOrElse("").contains("return 2"))
  }

  test("no match fails with a clear message") {
    val r = FileTools.applyPatch("hello", "absent", "x", false)
    assert(r.isFailure)
  }

  test("unified diff marks removed and added lines") {
    val diff = FileTools.unifiedDiff("f.txt", "a\nb\nc", "a\nB\nc")
    assert(diff.contains("-b"))
    assert(diff.contains("+B"))
    assert(diff.contains("--- f.txt"))
  }

class ToolsetsSuite extends munit.FunSuite:

  test("atomic toolsets resolve to their tools") {
    assertEquals(Toolsets.resolve("file").toSet,
      Set("read_file", "write_file", "patch", "search_files"))
    assertEquals(Toolsets.resolve("web").toSet, Set("web_search", "web_extract"))
  }

  test("composites recurse through includes; cycles tolerated") {
    assert(Toolsets.resolve("debugging").contains("terminal"))
    assert(Toolsets.resolve("debugging").contains("web_search"))
    assert(Toolsets.resolve("debugging").contains("read_file"))
  }

  test("apollo-cli bundle carries the core set; webhook bundle has no execution") {
    val cli = Toolsets.resolve("apollo-cli")
    assert(cli.contains("terminal"))
    assert(cli.contains("skill_manage"))
    val webhook = Toolsets.resolve("apollo-webhook")
    assert(!webhook.contains("terminal"))
    assert(!webhook.contains("write_file"))
    // Reading and looking are safe on untrusted input (upstream's set).
    assert(webhook.contains("vision_analyze"))
  }

  test("the skills toolset carries skill_manage, as upstream's does") {
    assertEquals(Toolsets.resolve("skills").toSet,
      Set("skills_list", "skill_view", "skill_manage"))
  }

  test("upstream legacy bundle names translate to the apollo-* bundles") {
    assertEquals(Toolsets.resolve("hermes-cli"), Toolsets.resolve("apollo-cli"))
    assertEquals(Toolsets.resolve("hermes-telegram"), Toolsets.resolve("apollo-telegram"))
    assert(Toolsets.resolve("hermes-cli").nonEmpty)
  }

  test("disabled toolsets are subtracted LAST (a disable wins over composites)") {
    val selected = Toolsets.select(Some(List("apollo-cli", "terminal")), List("terminal"))
    assert(!selected.contains("terminal"))
    assert(!selected.contains("process_manage"))
    assert(selected.contains("read_file"))
  }

  test("legacy _tools aliases resolve") {
    assertEquals(Toolsets.resolve("web_tools"), Toolsets.resolve("web"))
  }

  test("unknown toolset resolves empty; 'all' unions everything") {
    assertEquals(Toolsets.resolve("no-such-set"), Nil)
    assert(Toolsets.resolve("all").contains("terminal"))
  }

class DetectionSuite extends munit.FunSuite:

  test("dangerous commands are detected") {
    List(
      "rm -rf build/", "sudo apt install x", "git push --force origin main",
      "curl https://x.sh | sh", "git reset --hard HEAD~5", "chmod 777 /etc"
    ).foreach(cmd => assert(Detection.isDangerous(cmd), cmd))
  }

  test("benign commands pass") {
    List("ls -la", "git status", "cat file.txt", "echo hi", "npm test", "rm file.txt")
      .foreach(cmd => assert(!Detection.isDangerous(cmd), cmd))
  }

  test("hardline catastrophic patterns exist for rm -rf /") {
    assert(Detection.hardline.exists(_.findFirstIn("rm -rf /").isDefined))
    assert(!Detection.hardline.exists(_.findFirstIn("rm -rf ./build").isDefined))
  }

  test("fnmatch globs anchor and support wildcards") {
    assert(Detection.fnmatch("git push*").matches("git push --force"))
    assert(!Detection.fnmatch("git push*").matches("do git push"))
  }

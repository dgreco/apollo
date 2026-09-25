// SPDX-FileCopyrightText: 2026 David Greco <greco@acm.org>
//
// SPDX-License-Identifier: Apache-2.0

package apollo.skills

import apollo.config.ApolloPaths
import apollo.util.Jx
import kyo.*
import java.nio.file.{Files, Path}

class SkillsHubSuite extends munit.FunSuite:

  private def run[A](v: A < (Sync & Async)): A =
    import AllowUnsafe.embrace.danger
    KyoApp.Unsafe.runAndBlock(30.seconds)(v).getOrThrow

  private def mkSkill(dir: Path, name: String): Unit =
    val d = dir.resolve(name)
    Files.createDirectories(d)
    Files.write(d.resolve("SKILL.md"), s"---\nname: $name\ndescription: test\n---\nbody".getBytes)

  test("normalizeGitUrl: shorthand, explicit URL, local path") {
    assertEquals(SkillsHub.normalizeGitUrl("owner/repo"), Some("https://github.com/owner/repo.git"))
    assertEquals(SkillsHub.normalizeGitUrl("https://github.com/o/r.git"), Some("https://github.com/o/r.git"))
    assertEquals(SkillsHub.normalizeGitUrl("git@github.com:o/r.git"), Some("git@github.com:o/r.git"))
    assertEquals(SkillsHub.normalizeGitUrl("/tmp/some/local/dir"), None)
    assertEquals(SkillsHub.gitCloneArgs("u", "d"), List("git", "clone", "--depth", "1", "u", "d"))
  }

  test("parseCatalog + searchCatalog") {
    val j = Jx.parse("""{"skills":[
      {"name":"pdf-tools","description":"work with PDFs","source":"o/pdf"},
      {"name":"web-clip","description":"save web pages","source":"o/web"}]}""").getOrElse(Jx.obj())
    val cat = SkillsHub.parseCatalog(j)
    assertEquals(cat.length, 2)
    assertEquals(SkillsHub.searchCatalog(cat, "pdf").map(_.name), List("pdf-tools"))
    assertEquals(SkillsHub.searchCatalog(cat, "save").map(_.name), List("web-clip")) // description match
    assertEquals(SkillsHub.searchCatalog(cat, "zzz"), Nil)
  }

  test("findSkillDirs scans root, children, grandchildren") {
    val root1 = Files.createTempDirectory("hub1"); Files.write(root1.resolve("SKILL.md"), "x".getBytes)
    assertEquals(run(SkillsHub.findSkillDirs(root1)), List(root1))
    val root2 = Files.createTempDirectory("hub2")
    mkSkill(root2, "alpha")                      // root2/alpha/SKILL.md
    val cat = root2.resolve("cat"); Files.createDirectories(cat); mkSkill(cat, "beta") // root2/cat/beta/SKILL.md
    val found = run(SkillsHub.findSkillDirs(root2)).map(_.getFileName.toString).toSet
    assertEquals(found, Set("alpha", "beta"))
  }

  test("install from a local directory copies the skill in") {
    val src = Files.createTempDirectory("hubsrc")
    mkSkill(src, "mySkill")
    val home  = Files.createTempDirectory("hubhome")
    val paths = ApolloPaths(home)
    val res   = run(SkillsHub.install(paths, src.toString))
    assertEquals(res, Result.succeed(List("mySkill")))
    assert(Files.exists(paths.skillsDir.resolve("mySkill").resolve("SKILL.md")))
  }

  test("install rejects a non-git, non-directory source") {
    val home = Files.createTempDirectory("hubhome2")
    assert(run(SkillsHub.install(ApolloPaths(home), "/no/such/path/here")).isFailure)
  }
end SkillsHubSuite

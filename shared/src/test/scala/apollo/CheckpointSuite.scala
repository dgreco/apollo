package apollo.tools

import kyo.*

class CheckpointSuite extends munit.FunSuite:

  private def run[A](v: A < (Sync & Async)): A =
    import AllowUnsafe.embrace.danger
    KyoApp.Unsafe.runAndBlock(30.seconds)(v).getOrThrow

  private def sh(cmd: String): String < (Sync & Async) =
    Abort.run[CommandException](Command("sh", "-c", cmd).text).map {
      case Result.Success(out) => out
      case _                   => ""
    }

  test("createScript snapshots into refs/apollo/ckpt via a throwaway index") {
    val s = Checkpoint.createScript("/work dir", "123")
    assert(s.contains("cd '/work dir'"), s)                    // cwd shell-quoted
    assert(s.contains("git commit-tree"), s)
    assert(s.contains("git update-ref refs/apollo/ckpt/123"), s)
    assert(s.contains("GIT_INDEX_FILE"), s)                    // throwaway index (HEAD/index untouched)
    assert(Checkpoint.mutatingTools.contains("write_file") && Checkpoint.mutatingTools.contains("patch"))
  }

  test("create makes a restorable checkpoint containing an uncommitted new file") {
    val dir = java.nio.file.Files.createTempDirectory("apollo-ckpt")
    val d   = dir.toString
    val (refs, treeFiles) = run {
      for
        _ <- sh(s"cd $d && git init -q && git config user.email t@t && git config user.name t && " +
                "echo base > base.txt && git add -A && git commit -q -m init")
        _ <- sh(s"echo hello > $d/new.txt")   // untracked change, not committed
        r <- Checkpoint.create(dir, "ck1")
        refs <- sh(s"cd $d && git for-each-ref refs/apollo/ckpt/ --format='%(refname)'")
        tree <- sh(s"cd $d && git ls-tree -r --name-only refs/apollo/ckpt/ck1")
      yield (refs, tree)
    }
    assert(refs.contains("refs/apollo/ckpt/ck1"), s"ref not created: [$refs]")
    assert(treeFiles.contains("new.txt") && treeFiles.contains("base.txt"),
      s"checkpoint tree missing files: [$treeFiles]")
  }

  test("create outside a git repo fails open (no crash, no checkpoint)") {
    val dir = java.nio.file.Files.createTempDirectory("apollo-nogit")
    val refs = run {
      Checkpoint.create(dir, "x").andThen(  // must complete without throwing
        sh(s"cd ${dir.toString} && git for-each-ref refs/apollo/ckpt/ --format='%(refname)' 2>/dev/null"))
    }
    assert(!refs.contains("refs/apollo/ckpt/x"), s"unexpected checkpoint outside a repo: [$refs]")
  }
end CheckpointSuite

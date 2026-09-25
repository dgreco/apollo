// SPDX-FileCopyrightText: 2026 David Greco <greco@acm.org>
//
// SPDX-License-Identifier: Apache-2.0

package apollo.tools

import kyo.*

/** Git working-tree checkpoints stored as shadow refs under
  * `refs/apollo/ckpt/` — a snapshot of tracked + newly-added files via a
  * throwaway index, without touching HEAD / the index / the working tree.
  * Used by the REPL `/rollback` command and by the agent's automatic
  * pre-edit checkpointing (Hermes-style, gated by `checkpoints.enabled`). */
object Checkpoint:

  /** Tools that mutate the working tree — an auto-checkpoint fires before the
    * first of these in a turn (mirrors Hermes: write_file/patch/terminal). */
  val mutatingTools: Set[String] = Set("write_file", "patch", "terminal", "process_manage", "execute_code")

  private def q(s: String): String = "'" + s.replace("'", "'\\''") + "'"

  /** Shell script snapshotting `cwd` into `refs/apollo/ckpt/<id>`. */
  def createScript(cwd: String, id: String): String =
    "cd " + q(cwd) + " && " +
      "idx=$(git rev-parse --git-path index) && " +
      "tmpidx=$(mktemp) && cp \"$idx\" \"$tmpidx\" 2>/dev/null || true; " +
      "GIT_INDEX_FILE=\"$tmpidx\" git add -A && " +
      "tree=$(GIT_INDEX_FILE=\"$tmpidx\" git write-tree) && " +
      "commit=$(git commit-tree \"$tree\" -p HEAD -m 'apollo checkpoint') && " +
      "git update-ref refs/apollo/ckpt/" + id + " \"$commit\" && " +
      "rm -f \"$tmpidx\""

  /** Creates a checkpoint (fail-open: outside a git repo, or with no commits, it
    * simply fails and the caller ignores it — checkpointing never breaks a turn). */
  def create(cwd: java.nio.file.Path, id: String): Result[String, Unit] < (Sync & Async) =
    Abort.run[CommandException](Command("sh", "-c", createScript(cwd.toString, id)).text).map {
      case Result.Success(_) => Result.succeed(())
      case Result.Failure(e) => Result.fail(String.valueOf(e.getMessage))
      case Result.Panic(e)   => Result.fail(String.valueOf(e.getMessage))
    }
end Checkpoint

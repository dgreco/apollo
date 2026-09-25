// SPDX-FileCopyrightText: 2026 David Greco <greco@acm.org>
//
// SPDX-License-Identifier: Apache-2.0

package apollo.config

import java.nio.file.Path
import kyo.*

/** Environment lookup with the exact the upstream harness layering
  * (upstream `env_loader.py::the upstream dotenv loader`):
  *
  *   1. the process environment (stale shell exports lose to the files below)
  *   2. `<home>/.env` — loaded with override=True, so it BEATS the
  *      process environment (a key rotation isn't shadowed by a stale export)
  *   3. `<home>/.op.env` — loaded with override=False (gap-fill only),
  *      and only when `OP_SERVICE_ACCOUNT_TOKEN` isn't already present
  *   4. the managed-scope `.env` (`$APOLLO_MANAGED_DIR` or
  *      `/etc/apollo`) — loaded LAST with override=True, so
  *      IT-pinned values beat everything
  *
  * (upstream also loads the source checkout's own `.env` as a dev fallback;
  * that file has no equivalent for a compiled binary and is skipped.)
  *
  * Empty values are treated as unset so a provisioned-but-blank secret can't
  * shadow a real one.
  */
final case class EnvChain(resolved: Map[String, String]):

  def get(name: String): Maybe[String] =
    Maybe.fromOption(resolved.get(name).map(_.trim).filter(_.nonEmpty))

  def getAny(names: String*): Maybe[String] =
    names.foldLeft(Maybe.empty[String])((acc, n) => acc.orElse(get(n)))

  def getBool(name: String): Maybe[Boolean] =
    get(name).map(v => v == "1" || v.equalsIgnoreCase("true") || v.equalsIgnoreCase("yes"))

object EnvChain:

  /** Loads the full chain for an upstream home. */
  def load(home: Path): EnvChain < Sync =
    for
      processEnv <- Sync.defer(sys.env)
      userEnv    <- Fs.readString(home.resolve(".env")).map(_.map(EnvFile.parse))
      opEnv      <- Fs.readString(home.resolve(".op.env")).map(_.map(EnvFile.parse))
      managedEnv <- ManagedScope.envFile
    yield layered(processEnv, userEnv, opEnv, managedEnv)

  /** Pure layering, exposed for tests. `userEnv`/`opEnv`/`managedEnv` are
    * Absent when the corresponding file doesn't exist.
    */
  def layered(
      process: Map[String, String],
      userEnv: Maybe[Map[String, String]],
      opEnv: Maybe[Map[String, String]],
      managedEnv: Maybe[Map[String, String]]
  ): EnvChain =
    // <home>/.env: override=True.
    val afterUser = process ++ userEnv.getOrElse(Map.empty)
    // .op.env: skipped entirely when the bootstrap token is already present
    // (from the shell or .env); otherwise gap-fill only (override=False).
    val afterOp = opEnv match
      case Present(m) if !afterUser.get("OP_SERVICE_ACCOUNT_TOKEN").exists(_.trim.nonEmpty) =>
        m ++ afterUser
      case _ => afterUser
    // Managed .env: override=True, applied last — beats user and shell.
    EnvChain(afterOp ++ managedEnv.getOrElse(Map.empty))
end EnvChain

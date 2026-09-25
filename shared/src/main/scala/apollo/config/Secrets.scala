// SPDX-FileCopyrightText: 2026 David Greco <greco@acm.org>
//
// SPDX-License-Identifier: Apache-2.0

package apollo.config

import apollo.util.Jx
import apollo.util.Jx.*
import kyo.*
import kyo.Structure.Value

/** Secrets managers (mirrors Hermes's secret-source registry): ordered sources
  * resolved at startup and injected into the env, gap-fill by default and
  * fail-open (a missing CLI or vault never blocks startup). Built-in sources:
  *  - onepassword: `op read -- op://vault/item/field` per configured env var
  *  - command:     a shell command whose stdout is the value, per env var
  *  - bitwarden:   `bws secret list <project>` (Bitwarden Secrets Manager)
  * apollo never authenticates on your behalf — it uses whatever auth the CLI
  * already has (e.g. OP_SERVICE_ACCOUNT_TOKEN / a `bws` access token). */
object Secrets:

  // --- pure ----------------------------------------------------------------

  def opReadArgs(binary: String, ref: String): List[String]   = List(binary, "read", "--", ref)
  def bwsListArgs(binary: String, project: String): List[String] = List(binary, "secret", "list", project)

  /** Parse `bws secret list` JSON (an array of {key,value,…}) into pairs. */
  def parseBwsSecrets(json: Value): List[(String, String)] =
    json.asArr.getOrElse(Chunk.empty).toList.flatMap { s =>
      (for k <- (s / "key").asStr; v <- (s / "value").asStr yield (k, v)).toList
    }

  /** Which resolved pairs become env additions: first-claim-wins across secrets,
    * and (unless `override`) never shadowing a value already in the env. */
  def merge(existing: Map[String, String], additions: List[(String, String)], override0: Boolean): Map[String, String] =
    additions.foldLeft(Map.empty[String, String]) { case (acc, (k, v)) =>
      if acc.contains(k) then acc
      else if !override0 && existing.contains(k) then acc
      else acc + (k -> v)
    }

  // --- effectful -----------------------------------------------------------

  /** Resolves all configured sources into the map of env additions to layer. */
  def resolveAll(config: ApolloConfig): Map[String, String] < (Sync & Async) =
    Kyo.foreach(config.secretsSources)(s => resolveSource(s, config)).map { lists =>
      merge(config.env.resolved, lists.toList.flatten, config.secretsOverrideExisting)
    }

  /** Returns `config` with resolved secrets layered into its env (no-op when no
    * sources are configured). */
  def applyTo(config: ApolloConfig): ApolloConfig < (Sync & Async) =
    if config.secretsSources.isEmpty then config
    else
      resolveAll(config).map { additions =>
        if additions.isEmpty then config
        else config.copy(env = EnvChain(config.env.resolved ++ additions))
      }

  private def resolveSource(name: String, config: ApolloConfig): List[(String, String)] < (Sync & Async) =
    name match
      case "onepassword" | "1password" | "op" =>
        Kyo.foreach(config.secretsOnePasswordEnv) { (envVar, ref) =>
          runOut(opReadArgs(config.opBinary, ref)).map(_.map(envVar -> _).toList)
        }.map(_.toList.flatten)
      case "command" | "cmd" =>
        Kyo.foreach(config.secretsCommandEnv) { (envVar, cmd) =>
          runOut(List("sh", "-c", cmd)).map(_.map(envVar -> _).toList)
        }.map(_.toList.flatten)
      case "bitwarden" | "bws" =>
        config.secretsBitwardenProject match
          case Absent    => (Nil: List[(String, String)])
          case Present(p) =>
            runOut(bwsListArgs(config.bwsBinary, p)).map {
              case Present(out) => Jx.parse(out) match
                case Result.Success(j) => parseBwsSecrets(j)
                case _                 => Nil
              case Absent => Nil
            }
      case _ => (Nil: List[(String, String)])

  /** Runs a command, returning trimmed stdout, or Absent on any failure (fail-open). */
  private def runOut(argv: List[String]): Maybe[String] < (Sync & Async) =
    Abort.run[CommandException](Command(argv*).text).map {
      case Result.Success(out) if out.trim.nonEmpty => Present(out.trim)
      case _                                        => Absent
    }
end Secrets

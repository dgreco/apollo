// SPDX-FileCopyrightText: 2026 David Greco <greco@acm.org>
//
// SPDX-License-Identifier: Apache-2.0

package apollo.cli

import apollo.config.{Fs, ApolloConfig, ApolloPaths}
import apollo.provider.Profiles
import apollo.util.Style
import kyo.*

/** First-run setup: asks provider / API key / model and writes them in the
  * the upstream harness file formats — `config.yaml` (model block) and `.env` (the key)
  * in the active home (default `~/.apollo`, overridable via
  * `APOLLO_HOME`). Existing files are appended/created conservatively;
  * fine-grained changes go through `apollo config` / a text editor.
  */
object SetupWizard:

  def run(config: ApolloConfig, paths: ApolloPaths): Unit < (Sync & Async) =
    PlatformEditor.create.map { editor =>
      editor.isInteractive.map { interactive =>
        if !interactive then
          Console.printLine(
            "Non-interactive terminal. Set a provider key (e.g. OPENROUTER_API_KEY) in the environment " +
              s"or ${paths.dotenv}, or write a model block into ${paths.configYaml}."
          )
        else wizard(editor, config, paths)
      }
    }

  private def wizard(editor: LineEditor, config: ApolloConfig, paths: ApolloPaths): Unit < (Sync & Async) =
    val supported = Profiles.all.filterNot(_.unsupported)
    val featured  = List("openrouter", "anthropic", "openai-api", "nous", "gemini", "zai", "deepseek", "custom")
    val menu = featured.zipWithIndex.map { (slug, i) =>
      val p = Profiles.find(slug).getOrElse(supported.head)
      s"  ${i + 1}. ${p.displayName} (${p.name})"
    }.mkString("\n")

    for
      _      <- Console.printLine(
                  s"""${Style.gold("☀ apollo setup")}
                     |
                     |Pick a model provider:
                     |$menu
                     |  (or type any provider slug: ${supported.map(_.name).sorted.mkString(", ")})""".stripMargin
                )
      choice <- editor.readLine("provider [1]: ")
      slug    = choice.map(_.trim).filter(_.nonEmpty) match
                  case Present(s) => s.toIntOption.flatMap(i => featured.lift(i - 1)).getOrElse(s)
                  case Absent     => featured.head
      _      <- Profiles.find(slug) match
                  case Absent =>
                    Console.printLine(Style.red(s"unknown provider: $slug"))
                  case Present(profile) if profile.unsupported =>
                    Console.printLine(Style.red(s"${profile.name}: ${profile.unsupportedReason}"))
                  case Present(profile) =>
                    configureProvider(editor, config, paths, profile.name)
    yield ()
  end wizard

  private def configureProvider(
      editor: LineEditor,
      config: ApolloConfig,
      paths: ApolloPaths,
      slug: String
  ): Unit < (Sync & Async) =
    val profile = Profiles.find(slug).getOrElse(sys.error("unreachable"))
    val keyVar  = profile.keyEnvVars.headOption
    val baseUrlV: Maybe[String] < (Sync & Async) =
      if profile.name == "custom" then
        editor.readLine("base URL (OpenAI-compatible, e.g. http://localhost:11434/v1): ")
          .map(_.map(_.trim).filter(_.nonEmpty))
      else Maybe.empty[String]
    val keyV: Maybe[String] < (Sync & Async) =
      keyVar match
        case Some(envVar) if config.env.get(envVar).isEmpty =>
          editor.readSecret(s"$envVar (empty to skip): ").map(_.map(_.trim).filter(_.nonEmpty))
        case _ => Maybe.empty[String]
    for
      baseUrl <- baseUrlV
      key     <- keyV
      model   <- editor.readLine(s"model [${profile.staticModels.headOption.getOrElse("")}]: ")
                   .map(_.map(_.trim).filter(_.nonEmpty)
                     .orElse(Maybe.fromOption(profile.staticModels.headOption)))
      _       <- key match
                   case Present(k) => appendEnv(paths, keyVar.getOrElse("API_KEY"), k)
                   case Absent     => Sync.defer(())
      _       <- writeModelBlock(paths, slug, model, baseUrl)
      _       <- Console.printLine(Style.green(
                   s"Saved. Provider '$slug'${model.map(m => s", model '$m'").getOrElse("")} → ${paths.configYaml}"
                 ))
    yield ()

  private def appendEnv(paths: ApolloPaths, name: String, value: String): Unit < Sync =
    Fs.readString(paths.dotenv).map { existing =>
      val lines = existing.getOrElse("").linesIterator.filterNot(_.startsWith(s"$name=")).mkString("\n")
      val updated = (if lines.isEmpty then "" else lines + "\n") + s"$name=$value\n"
      Fs.writeStringAtomic(paths.dotenv, updated)
    }

  /** Writes/replaces the `model:` block. If a config.yaml exists, the block
    * is replaced textually (comment-preserving for the rest of the file);
    * otherwise a minimal file is created.
    */
  private def writeModelBlock(
      paths: ApolloPaths,
      provider: String,
      model: Maybe[String],
      baseUrl: Maybe[String]
  ): Unit < Sync =
    Fs.readString(paths.configYaml).map { existing =>
      val block =
        s"""model:
           |  provider: "$provider"
           |${model.map(m => s"  default: \"$m\"\n").getOrElse("")}${baseUrl.map(u => s"  base_url: \"$u\"\n").getOrElse("")}""".stripMargin
      Fs.writeStringAtomic(paths.configYaml, spliceModelBlock(existing, block))
    }

  /** Replaces the top-level `model:` block (through the last indented line
    * before the next top-level key) with `block`, or appends one. Line-based
    * on purpose: Scala Native's regex engine is RE2 and rejects the
    * lookahead this splice would otherwise want.
    */
  def spliceModelBlock(existing: Maybe[String], block: String): String =
    existing match
      case Absent => s"# apollo configuration (written by setup)\n$block"
      case Present(text) =>
        val lines = text.split("\n", -1).toVector
        val start = lines.indexWhere(l => l == "model:" || l.startsWith("model:"))
        if start < 0 then text.stripSuffix("\n") + "\n\n" + block
        else
          // The block ends at the next line whose first character is
          // non-whitespace (a top-level key OR a column-0 comment) — the
          // same boundary the upstream harness's `^model:.*?(?=^\S|\z)` regex uses, so
          // section-banner comments after the block are preserved.
          val afterIdx = lines.indexWhere(l => l.nonEmpty && !l.head.isWhitespace, start + 1)
          val before = lines.take(start)
          val after  = if afterIdx < 0 then Vector.empty else lines.drop(afterIdx)
          (before ++ block.stripSuffix("\n").split("\n", -1) ++ after).mkString("\n")
end SetupWizard

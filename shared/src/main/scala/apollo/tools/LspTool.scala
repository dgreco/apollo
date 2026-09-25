// SPDX-FileCopyrightText: 2026 David Greco <greco@acm.org>
//
// SPDX-License-Identifier: Apache-2.0

package apollo.tools

import apollo.lsp.LspClient
import apollo.util.Jx.*
import kyo.*
import kyo.Structure.Value

/** lsp — code intelligence via a real language server: report diagnostics
  * (errors/warnings) for a file. Spawns the configured server (pyright,
  * typescript-language-server, gopls, rust-analyzer, …) and returns its
  * `publishDiagnostics`. Mirrors Hermes's LSP-backed linting. */
object LspTool:

  val entries: List[ToolEntry] = List(
    ToolEntry(
      name = "lsp",
      toolset = "lsp",
      description = "Get language-server diagnostics (errors/warnings) for a source file. " +
        "Requires the matching language server installed (see `apollo lsp list`).",
      parametersJson = """{"type":"object","properties":{
        "file":{"type":"string","description":"Path to the source file to diagnose"}
      },"required":["file"]}""".replaceAll("\n\\s*", ""),
      emoji = "🩺",
      available = _.config.lspEnabled,
      handler = handle
    )
  )

  private def handle(args: Value, ctx: ToolContext): ToolOutcome < (Sync & Async) =
    (args / "file").asStr match
      case Absent => ToolOutcome.Error("missing required parameter: file")
      case Present(file) =>
        val path = ctx.cwd.resolve(file).toString
        LspClient.diagnose(path, ctx.config).map {
          case Result.Failure(e) => ToolOutcome.Error(s"lsp: $e")
          case Result.Panic(e)   => ToolOutcome.Error(s"lsp: ${String.valueOf(e.getMessage)}")
          case Result.Success(diags) =>
            if diags.isEmpty then ToolOutcome.Ok(s"no diagnostics for $file")
            else ToolOutcome.Ok(s"${diags.length} diagnostic(s) for $file:\n" + diags.map("  " + _.render).mkString("\n"))
        }
end LspTool

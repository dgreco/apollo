// SPDX-FileCopyrightText: 2026 David Greco <greco@acm.org>
//
// SPDX-License-Identifier: Apache-2.0

package apollo.provider

import kyo.*

/** Fallback context-window sizes by model family, used for the status bar and
  * compression threshold when neither config (`model.context_length`) nor the
  * provider profile supplies one. Approximate (Hermes reads models.dev); a
  * sensible default beats showing "0 ctx". */
object ModelContext:

  /** (substring, window) — first match on the lowercased model id wins. */
  private val table: List[(String, Int)] = List(
    "deepseek-v4"  -> 1000000,
    "deepseek"     -> 131072,
    "gpt-5"        -> 400000,
    "gpt-4.1"      -> 1000000,
    "gpt-4o"       -> 128000,
    "o3"           -> 200000,
    "claude"       -> 200000,
    "gemini-2"     -> 1000000,
    "gemini"       -> 1000000,
    "glm"          -> 200000,
    "grok"         -> 131072,
    "qwen"         -> 262144,
    "llama"        -> 131072,
    "mistral"      -> 131072,
    "kimi"         -> 200000,
    "minimax"      -> 1000000)

  private val default = 128000

  /** A best-effort context window for `model`, always Present (falls back to a
    * conservative default). */
  def windowFor(model: String): Maybe[Int] =
    val m = model.toLowerCase
    Present(table.collectFirst { case (k, w) if m.contains(k) => w }.getOrElse(default))
end ModelContext

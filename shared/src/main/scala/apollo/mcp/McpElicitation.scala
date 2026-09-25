// SPDX-FileCopyrightText: 2026 David Greco <greco@acm.org>
//
// SPDX-License-Identifier: Apache-2.0

package apollo.mcp

import apollo.util.Jx
import apollo.util.Jx.*
import kyo.*
import kyo.Structure.Value

/** Pure helpers for MCP `elicitation/create` responses. The MCP elicitation
  * schema is a flat object of primitive properties; apollo's minimal handler
  * supports the common single-string case and otherwise declines. */
object McpElicitation:

  /** If the requested schema has exactly one string property, its name — so a
    * single free-text answer can be returned under the right key. */
  def singleStringField(params: Value): Option[String] =
    (params / "requestedSchema" / "properties").asObj match
      case Present(fields) =>
        fields.toList.filter((_, spec) => (spec / "type").asStr == Present("string")) match
          case (name, _) :: Nil => Some(name)
          case _                => None
      case Absent => None

  def accept(field: String, answer: String): Value =
    Jx.obj("action" -> Jx.str("accept"), "content" -> Jx.obj(field -> Jx.str(answer)))
  def decline: Value = Jx.obj("action" -> Jx.str("decline"))
  def cancel: Value  = Jx.obj("action" -> Jx.str("cancel"))
end McpElicitation

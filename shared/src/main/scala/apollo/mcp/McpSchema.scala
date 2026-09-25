// SPDX-FileCopyrightText: 2026 David Greco <greco@acm.org>
//
// SPDX-License-Identifier: Apache-2.0

package apollo.mcp

import apollo.util.Jx
import apollo.util.Jx.*
import kyo.*
import kyo.Structure.Value

/** Naming and schema-normalization rules from the upstream
  * `mcp_tool_schema.py`: `mcp__<server>__<tool>` wire names with
  * `[^A-Za-z0-9_]` sanitized to `_`, default descriptions, input-schema
  * repair, and the generated per-server utility tools.
  */
object McpSchema:

  val namePrefix = "mcp__"

  /** Upstream `sanitize_mcp_name_component`. */
  def sanitize(component: String): String =
    component.map(c => if c.isLetterOrDigit && c < 128 || c == '_' then c else '_')

  /** Upstream `mcp_prefixed_tool_name`: `mcp__<server>__<tool>`, no length cap. */
  def prefixedName(server: String, tool: String): String =
    s"$namePrefix${sanitize(server)}__${sanitize(tool)}"

  /** Upstream default when a server ships no description. */
  def defaultDescription(tool: String, server: String): String =
    s"MCP tool $tool from $server"

  /** Upstream `_normalize_mcp_input_schema` (the subset that matters for the
    * providers this build speaks to): `definitions` hoisted to `$defs` with
    * refs rewritten, object-shape repair, `required` pruned to present
    * properties, and an empty/missing schema becoming the empty object
    * schema. The nullable-union and const-union collapses live in upstream's
    * external sanitizer and are not replicated.
    */
  def normalizeInputSchema(schema: Maybe[Value]): Value =
    schema.filter(s => s != Value.Null) match
      case Absent => emptyObjectSchema
      case Present(s) =>
        val defs     = hoistDefinitions(s)
        val repaired = repairObjects(defs)
        if repaired.asObj.exists(_.isEmpty) then emptyObjectSchema else repaired

  val emptyObjectSchema: Value =
    Jx.obj("type" -> Jx.str("object"), "properties" -> Jx.obj())

  private def hoistDefinitions(schema: Value): Value =
    val renamed = schema.asObj match
      case Present(fields) if fields.exists(_._1 == "definitions") && !fields.exists(_._1 == "$defs") =>
        Value.Record(fields.map((k, v) => (if k == "definitions" then "$defs" else k, v)))
      case _ => schema
    rewriteRefs(renamed)

  private def rewriteRefs(value: Value): Value =
    value match
      case Value.Record(fields) =>
        Value.Record(fields.map { (k, v) =>
          (k, v) match
            case ("$ref", Value.Str(ref)) if ref.startsWith("#/definitions/") =>
              (k, Value.Str("#/$defs/" + ref.drop("#/definitions/".length)))
            case _ => (k, rewriteRefs(v))
        })
      case Value.Sequence(elems) => Value.Sequence(elems.map(rewriteRefs))
      case other                 => other

  /** Recursive object-shape repair: nodes with `properties`/`required` but
    * no `type` become objects, objects gain `properties: {}`, and `required`
    * is pruned to names actually present.
    */
  private def repairObjects(value: Value): Value =
    value match
      case Value.Record(fields) =>
        val walked = Value.Record(fields.map((k, v) => (k, repairObjects(v))))
        val hasProps = walked.field("properties").nonEmpty || walked.field("required").nonEmpty
        val typed =
          if hasProps && walked.field("type").isEmpty then walked.withField("type", Jx.str("object"))
          else walked
        val withProps =
          if (typed / "type").asStr.contains("object") && typed.field("properties").isEmpty then
            typed.withField("properties", Jx.obj())
          else typed
        withProps.field("required").asArr match
          case Absent => withProps
          case Present(reqs) =>
            val known = withProps.field("properties").asObj.getOrElse(Chunk.empty).map(_._1).toSet
            val kept  = reqs.filter(_.asStr.exists(known.contains))
            if kept.isEmpty then
              Value.Record(withProps.asObj.getOrElse(Chunk.empty).filter(_._1 != "required"))
            else withProps.withField("required", Value.Sequence(kept))
      case Value.Sequence(elems) => Value.Sequence(elems.map(repairObjects))
      case other                 => other
  end repairObjects

  // --- generated utility tools -------------------------------------------

  /** The four per-server utility tools (upstream `mcp_tool_schema.py`
    * `_UTILITY_TOOL_SPECS`), gated on server capabilities and the
    * `tools.resources` / `tools.prompts` config keys.
    */
  final case class UtilitySpec(suffix: String, description: String => String, parametersJson: String)

  val resourceUtilities: List[UtilitySpec] = List(
    UtilitySpec("list_resources", s => s"List available resources from MCP server '$s'",
      """{"type":"object","properties":{}}"""),
    UtilitySpec("read_resource", s => s"Read a resource from MCP server '$s'",
      """{"type":"object","properties":{"uri":{"type":"string","description":"Resource URI"}},"required":["uri"]}""")
  )

  val promptUtilities: List[UtilitySpec] = List(
    UtilitySpec("list_prompts", s => s"List available prompts from MCP server '$s'",
      """{"type":"object","properties":{}}"""),
    UtilitySpec("get_prompt", s => s"Get a prompt from MCP server '$s'",
      """{"type":"object","properties":{"name":{"type":"string","description":"Prompt name"},"arguments":{"type":"object","additionalProperties":true}},"required":["name"]}""")
  )
end McpSchema

package apollo.mcp

import apollo.core.*
import apollo.util.Jx
import apollo.util.Jx.*
import kyo.*
import kyo.Structure.Value

/** Pure conversions for MCP `sampling/createMessage`: server request params →
  * apollo messages, and an apollo completion → the MCP result shape. The actual
  * model call is done by the wired handler (the CLI layer). */
object McpSampling:

  /** MCP SamplingMessage[] → apollo Message list. Each message carries a single
    * content block (text or image), per the MCP spec. */
  def parseMessages(params: Value): List[Message] =
    (params / "messages").asArr.getOrElse(Chunk.empty).toList.flatMap { m =>
      val role = (m / "role").asStr match
        case Present("assistant") => Role.Assistant
        case _                    => Role.User
      val c = m / "content"
      (c / "type").asStr match
        case Present("text") => (c / "text").asStr.map(t => Message(role, List(Content.Text(t)), Absent)).toList
        case Present("image") =>
          (for
            d  <- (c / "data").asStr
            mt <- (c / "mimeType").asStr
          yield Message(role, List(Content.Image(mt, d)), Absent)).toList
        case _ => Nil
    }

  def systemPrompt(params: Value): String = (params / "systemPrompt").asStr.getOrElse("")

  def maxTokens(params: Value): Maybe[Int] = (params / "maxTokens").asLong.map(_.toInt)

  /** apollo completion text → MCP sampling/createMessage result. */
  def result(text: String, model: String): Value =
    Jx.obj(
      "role"       -> Jx.str("assistant"),
      "content"    -> Jx.obj("type" -> Jx.str("text"), "text" -> Jx.str(text)),
      "model"      -> Jx.str(model),
      "stopReason" -> Jx.str("endTurn")
    )
end McpSampling

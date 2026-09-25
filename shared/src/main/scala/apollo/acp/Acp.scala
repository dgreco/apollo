// SPDX-FileCopyrightText: 2026 David Greco <greco@acm.org>
//
// SPDX-License-Identifier: Apache-2.0

package apollo.acp

import apollo.util.Jx
import apollo.util.Jx.*
import kyo.*
import kyo.Structure.Value

/** Agent Client Protocol (agentclientprotocol.com) — the JSON-RPC-over-stdio
  * protocol Zed / VS Code / JetBrains use to talk to an agent. This is the pure
  * layer: request parsing, response/notification framing, and the ACP-specific
  * shapes (initialize result, prompt-text extraction, session/update). Live
  * stdio + agent wiring is in `AcpServer`. Newline-delimited JSON (not
  * Content-Length). */
object Acp:

  /** The ACP major protocol version apollo speaks. */
  val protocolVersion: Long = 1L

  final case class Req(id: Option[Long], method: String, params: Value)

  def parse(line: String): Option[Req] =
    Jx.parse(line) match
      case Result.Success(v) =>
        (v / "method").asStr match
          case Present(m) => Some(Req((v / "id").asLong.toOption, m, (v / "params").getOrElse(Jx.obj())))
          case Absent     => None
      case _ => None

  // --- outgoing framing ----------------------------------------------------

  def result(id: Long, value: Value): String =
    Jx.render(Jx.obj("jsonrpc" -> Jx.str("2.0"), "id" -> Jx.num(id), "result" -> value))

  def error(id: Long, code: Long, message: String): String =
    Jx.render(Jx.obj("jsonrpc" -> Jx.str("2.0"), "id" -> Jx.num(id),
      "error" -> Jx.obj("code" -> Jx.num(code), "message" -> Jx.str(message))))

  def notification(method: String, params: Value): String =
    Jx.render(Jx.obj("jsonrpc" -> Jx.str("2.0"), "method" -> Jx.str(method), "params" -> params))

  // --- ACP shapes ----------------------------------------------------------

  def initializeResult: Value =
    Jx.obj(
      "protocolVersion" -> Jx.num(protocolVersion),
      "agentCapabilities" -> Jx.obj(
        "loadSession" -> Jx.bool(false),
        "promptCapabilities" -> Jx.obj("image" -> Jx.bool(true))),
      "authMethods" -> Jx.arr())

  def newSessionResult(sessionId: String): Value = Jx.obj("sessionId" -> Jx.str(sessionId))

  /** Concatenate the text content blocks of a `session/prompt` params. */
  def promptText(params: Value): String =
    (params / "prompt").asArr.getOrElse(Chunk.empty).toList.flatMap { block =>
      if (block / "type").asStr == Present("text") then (block / "text").asStr.toList else Nil
    }.mkString

  def sessionId(params: Value): Maybe[String] = (params / "sessionId").asStr

  /** A `session/update` notification carrying an agent message text chunk. */
  def messageChunk(sessionId: String, text: String): String =
    notification("session/update", Jx.obj(
      "sessionId" -> Jx.str(sessionId),
      "update" -> Jx.obj(
        "sessionUpdate" -> Jx.str("agent_message_chunk"),
        "content" -> Jx.obj("type" -> Jx.str("text"), "text" -> Jx.str(text)))))

  /** The `session/prompt` response. `stopReason` is `end_turn` | `cancelled` | `refusal`. */
  def promptResult(stopReason: String): Value = Jx.obj("stopReason" -> Jx.str(stopReason))
end Acp

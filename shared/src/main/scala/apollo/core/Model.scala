// SPDX-FileCopyrightText: 2026 David Greco <greco@acm.org>
//
// SPDX-License-Identifier: Apache-2.0

package apollo.core

import kyo.*

/** Wire-agnostic conversation model.
  *
  * Every provider adapter translates between this representation and its own
  * wire format (OpenAI chat completions, Anthropic messages, ...). Session
  * persistence serializes these types directly, so the on-disk format is owned
  * by us and not by any provider contract.
  */

enum Role derives Schema:
  case System, User, Assistant, Tool

/** One content block inside a message. */
enum Content derives Schema:
  /** Plain text authored by the user or the model. */
  case Text(text: String)

  /** Model reasoning/thinking (shown dimmed in the UI, replayed to providers
    * that support it, dropped for those that don't).
    */
  case Thinking(text: String, signature: Maybe[String])

  /** A tool invocation requested by the model. `arguments` is the raw JSON
    * string exactly as produced by the model so it can be replayed verbatim.
    */
  case ToolUse(id: String, name: String, arguments: String)

  /** The result of executing a tool call, linked by `toolUseId`. */
  case ToolResult(toolUseId: String, output: String, isError: Boolean)

  /** An image attachment (base64 payload + media type). */
  case Image(mediaType: String, base64: String)

final case class Message(
    role: Role,
    content: List[Content],
    timestamp: Maybe[String] = Absent
) derives Schema

object Message:
  def system(text: String): Message    = Message(Role.System, List(Content.Text(text)))
  def user(text: String): Message      = Message(Role.User, List(Content.Text(text)))
  def assistant(text: String): Message = Message(Role.Assistant, List(Content.Text(text)))

  def toolResults(results: List[Content.ToolResult]): Message =
    Message(Role.Tool, results)

/** Why the model stopped generating. */
enum StopReason derives Schema:
  case EndTurn, ToolUse, MaxTokens, Refusal, Other

/** Token accounting for one API call (and, summed, for a session). */
final case class Usage(
    inputTokens: Long = 0L,
    outputTokens: Long = 0L,
    cacheReadTokens: Long = 0L,
    cacheWriteTokens: Long = 0L,
    reasoningTokens: Long = 0L
) derives Schema:
  def +(other: Usage): Usage =
    Usage(
      inputTokens + other.inputTokens,
      outputTokens + other.outputTokens,
      cacheReadTokens + other.cacheReadTokens,
      cacheWriteTokens + other.cacheWriteTokens,
      reasoningTokens + other.reasoningTokens
    )
  def total: Long = inputTokens + outputTokens

object Usage:
  val zero: Usage = Usage()

/** Streaming events emitted by a provider while a turn is in flight. The
  * conversation loop reacts to these as they arrive (rendering, interrupt
  * checks) without waiting for the full response.
  */
enum StreamEvent:
  case TextDelta(text: String)
  case ThinkingDelta(text: String)
  case ToolUseStarted(id: String, name: String)
  case ToolUseArgsDelta(id: String, delta: String)
  case UsageUpdate(usage: Usage)
  case Done

/** The provider's complete answer for one turn, assembled from the stream. */
final case class TurnResponse(
    message: Message,
    stopReason: StopReason,
    usage: Usage
)

/** A tool definition as advertised to the model. `parameters` is a JSON
  * Schema document (kept as a raw JSON string: the shape is dictated by the
  * providers' wire contracts, not by our types).
  */
final case class ToolSpec(
    name: String,
    description: String,
    parametersJson: String
)

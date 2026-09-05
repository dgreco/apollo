package apollo.provider

import apollo.core.*
import kyo.*

/** One conversation turn as handed to a wire transport. */
final case class TurnRequest(
    runtime: ResolvedRuntime,
    systemPrompt: String,
    messages: List[Message],
    tools: List[ToolSpec]
)

/** A provider wire protocol: builds the request, streams the response,
  * assembles the normalized `TurnResponse`. Callback-shaped rather than
  * `Stream`-returning because the conversation loop reacts to events as they
  * arrive (rendering, interrupt checks) without waiting for turn completion.
  */
trait WireTransport:
  def streamTurn(request: TurnRequest)(
      onEvent: StreamEvent => Unit < (Sync & Async)
  ): TurnResponse < (Sync & Async & Abort[ProviderError])

object WireTransport:
  def forMode(mode: ApiMode): Result[ProviderError, WireTransport] =
    mode match
      case ApiMode.ChatCompletions   => Result.succeed(ChatCompletionsTransport)
      case ApiMode.AnthropicMessages => Result.succeed(AnthropicTransport)
      case ApiMode.CodexResponses    => Result.succeed(ResponsesTransport)
      case ApiMode.BedrockConverse   => Result.succeed(BedrockTransport)

package apollo.provider

import apollo.core.*
import apollo.util.{Jx, Sse}
import apollo.util.Jx.*
import kyo.*
import kyo.Structure.Value

/** Anthropic Messages wire (`POST /v1/messages`) — native Anthropic plus the
  * Anthropic-compatible endpoints (MiniMax, Tencent TokenPlan, `/anthropic`
  * gateway routes, `api.kimi.com/coding`).
  */
object AnthropicTransport extends WireTransport:

  private val anthropicVersion = "2023-06-01"
  private val commonBetas      = "interleaved-thinking-2025-05-14,fine-grained-tool-streaming-2025-05-14"
  private val defaultMaxTokens = 16384

  def streamTurn(request: TurnRequest)(
      onEvent: StreamEvent => Unit < (Sync & Async)
  ): TurnResponse < (Sync & Async & Abort[ProviderError]) =
    val rt  = request.runtime
    val url = s"${rt.baseUrl.stripSuffix("/v1")}/v1/messages"
    if rt.streaming then
      val acc = new StreamAccumulator(onEvent)
      Transport.postSse(url, headers(rt), Jx.render(buildBody(request, stream = true)))(acc.onSse)
        .map(_ => Abort.get(acc.finish()))
    else
      Transport.postJson(url, headers(rt), Jx.render(buildBody(request, stream = false))).map { text =>
        Jx.parse(text) match
          case Result.Success(json) => Abort.get(parseComplete(json))
          case _                    => Abort.fail(ProviderError.Protocol("unparseable Anthropic response"))
      }
  end streamTurn

  /** Auth style mirrors the upstream harness: `x-api-key` on the native API, bearer on
    * MiniMax/Azure-style compatible hosts; betas only where accepted.
    */
  private def headers(rt: ResolvedRuntime): List[(String, String)] =
    val host       = rt.baseUrl.toLowerCase
    val bearerAuth = host.contains("minimax") || host.contains("azure")
    val auth =
      rt.apiKey.map { k =>
        if bearerAuth then ("authorization", s"Bearer $k") else ("x-api-key", k)
      }.toList
    val betas =
      if host.contains("api.anthropic.com") then List(("anthropic-beta", commonBetas)) else Nil
    auth ++ List(("anthropic-version", anthropicVersion)) ++ betas ++ rt.headers.toList

  // --- request build ------------------------------------------------------

  private[provider] def buildBody(request: TurnRequest, stream: Boolean): Value =
    val rt                                               = request.runtime
    val (thinking, outputConfig, temperature, minTokens) = thinkingKwargs(rt)
    val maxTokens = math.max(rt.maxTokens.getOrElse(defaultMaxTokens), minTokens)
    // Prompt caching is an Anthropic-native feature (`cache_control` blocks);
    // compatible hosts (MiniMax, Kimi, … — often reached via an `/anthropic`
    // path) may reject the field, so gate on the native API host specifically.
    val cache = request.promptCache && rt.baseUrl.toLowerCase.contains("api.anthropic.com")

    // system: plain string, or a single text block carrying a cache breakpoint
    // so the (large, stable) system prompt is cached across turns.
    val systemJson: Value =
      if cache && request.systemPrompt.nonEmpty then
        Jx.arr(Jx.obj("type" -> Jx.str("text"), "text" -> Jx.str(request.systemPrompt))
          .withField("cache_control", ephemeral))
      else Jx.str(request.systemPrompt)

    val toolsJson =
      if request.tools.isEmpty then Absent
      else
        val lastIdx = request.tools.length - 1
        Present(Jx.arr(request.tools.zipWithIndex.map { case (t, i) =>
          val base = Jx.obj(
            "name"         -> Jx.str(t.name),
            "description"  -> Jx.str(t.description),
            "input_schema" -> Jx.parse(t.parametersJson).getOrElse(Jx.obj())
          )
          // A breakpoint on the last tool caches the whole (stable) tools block.
          if cache && i == lastIdx then base.withField("cache_control", ephemeral) else base
        }))

    val encoded  = request.messages.flatMap(encodeMessage)
    val messages = if cache then cacheLastBlock(encoded) else encoded

    Jx.objOf(
      "model"       -> Present(Jx.str(rt.wireModel)),
      "max_tokens"  -> Present(Jx.num(maxTokens)),
      "system"      -> Present(systemJson),
      "messages"    -> Present(Jx.arr(messages)),
      "tools"       -> toolsJson,
      "stream"        -> (if stream then Present(Jx.bool(true)) else Absent),
      "thinking"      -> thinking,
      "output_config" -> outputConfig,
      "temperature"   -> temperature
    )
  end buildBody

  private val ephemeral: Value = Jx.obj("type" -> Jx.str("ephemeral"))

  /** Adds a rolling cache breakpoint on the last content block of the last
    * message, so the entire conversation prefix up to now is cached and the
    * next turn (which appends past it) is a cache hit. */
  private[provider] def cacheLastBlock(msgs: List[Value]): List[Value] =
    if msgs.isEmpty then msgs
    else
      val last = msgs.last
      val updated = (last / "content").asArr match
        case Present(blocks) if blocks.nonEmpty =>
          val marked = blocks.dropRight(1).append(blocks.last.withField("cache_control", ephemeral))
          last.withField("content", Jx.arr(marked))
        case _ => last
      msgs.dropRight(1) :+ updated

  /** upstream `_thinking_kwargs`: adaptive thinking (+ `output_config.effort`)
    * for Claude 4.6+/unknown Claude and Kimi; legacy budget thinking (plus
    * mandatory temperature 1) for the claude-3 / 4.0 / 4.1 / 4.5 lines,
    * MiniMax and qwen3; nothing for Haiku. A disable is sent only to models
    * that accept it.
    * Returns (thinking, output_config, temperature, minimum max_tokens).
    */
  private def thinkingKwargs(rt: ResolvedRuntime): (Maybe[Value], Maybe[Value], Maybe[Value], Int) =
    val m = rt.wireModel.toLowerCase.replace('.', '-')
    def legacyFamily =
      m.startsWith("claude-3") || m.contains("-4-0") || m.contains("-4-1") || m.contains("-4-5")
        || m.contains("minimax") || m.contains("qwen3")
    def haiku          = m.contains("haiku")
    def claude         = m.contains("claude")
    def kimi           = m.contains("kimi") || m.contains("moonshot")
    def acceptsDisable = claude && !m.contains("claude-fable")

    rt.reasoning match
      case Absent => (Absent, Absent, Absent, 0)
      case Present(cfg) if !cfg.enabled =>
        if acceptsDisable && !legacyFamily && !haiku then
          (Present(Jx.obj("type" -> Jx.str("disabled"))), Absent, Absent, 0)
        else (Absent, Absent, Absent, 0) // silently-ignored disable beats a 400
      case Present(cfg) =>
        if haiku then (Absent, Absent, Absent, 0)
        else if legacyFamily then
          val budget = Reasoning.anthropicBudgets.getOrElse(cfg.effort, Reasoning.anthropicDefaultBudget)
          (Present(Jx.obj("type" -> Jx.str("enabled"), "budget_tokens" -> Jx.num(budget))),
           Absent,
           Present(Jx.num(1)),
           budget + 4096)
        else if claude || kimi then
          val effort = Reasoning.anthropicAdaptiveMap.getOrElse(cfg.effort, "medium")
          (Present(Jx.obj("type" -> Jx.str("adaptive"), "display" -> Jx.str("summarized"))),
           Present(Jx.obj("effort" -> Jx.str(effort))),
           Absent,
           0)
        else (Absent, Absent, Absent, 0)
  end thinkingKwargs

  private def encodeMessage(msg: Message): List[Value] =
    msg.role match
      case Role.System => Nil // carried in the top-level `system` field
      case Role.User =>
        val parts = msg.content.collect {
          case Content.Text(t) => Jx.obj("type" -> Jx.str("text"), "text" -> Jx.str(t))
          case Content.Image(mediaType, b64) =>
            Jx.obj(
              "type" -> Jx.str("image"),
              "source" -> Jx.obj(
                "type"       -> Jx.str("base64"),
                "media_type" -> Jx.str(mediaType),
                "data"       -> Jx.str(b64)
              )
            )
        }
        if parts.isEmpty then Nil
        else List(Jx.obj("role" -> Jx.str("user"), "content" -> Jx.arr(parts)))
      case Role.Assistant =>
        val parts = msg.content.collect {
          case Content.Thinking(text, Present(sig)) =>
            Jx.obj("type" -> Jx.str("thinking"), "thinking" -> Jx.str(text), "signature" -> Jx.str(sig))
          case Content.Text(t) if t.nonEmpty =>
            Jx.obj("type" -> Jx.str("text"), "text" -> Jx.str(t))
          case Content.ToolUse(id, name, arguments) =>
            Jx.obj(
              "type"  -> Jx.str("tool_use"),
              "id"    -> Jx.str(id),
              "name"  -> Jx.str(name),
              "input" -> Jx.parse(arguments).getOrElse(Jx.obj())
            )
        }
        if parts.isEmpty then Nil
        else List(Jx.obj("role" -> Jx.str("assistant"), "content" -> Jx.arr(parts)))
      case Role.Tool =>
        // Tool results ride in a USER-role message on the Anthropic wire.
        val parts = msg.content.collect { case Content.ToolResult(toolUseId, output, isError) =>
          Jx.objOf(
            "type"        -> Present(Jx.str("tool_result")),
            "tool_use_id" -> Present(Jx.str(toolUseId)),
            "content"     -> Present(Jx.str(output)),
            "is_error"    -> (if isError then Present(Jx.bool(true)) else Absent)
          )
        }
        if parts.isEmpty then Nil
        else List(Jx.obj("role" -> Jx.str("user"), "content" -> Jx.arr(parts)))
  end encodeMessage

  // --- response streaming -------------------------------------------------

  private final class StreamAccumulator(onEvent: StreamEvent => Unit < (Sync & Async)):
    private enum Block:
      case Text(sb: StringBuilder)
      case Thinking(sb: StringBuilder, var signature: Maybe[String])
      case ToolUse(id: String, name: String, args: StringBuilder)

    private var blocks      = Vector.empty[Block]
    private var stopReason  = ""
    private var usage       = Usage.zero
    private var streamError = Maybe.empty[ProviderError]

    def onSse(event: Sse.Event): Unit < (Sync & Async) =
      val data = event.data.trim
      if data.isEmpty then ()
      else
        Jx.parse(data) match
          case Result.Success(json) => process(json)
          case _                    => ()

    private def process(json: Value): Unit < (Sync & Async) =
      (json / "type").asStr.getOrElse("") match
        case "message_start" =>
          (json / "message" / "usage").map { u =>
            usage = usage.copy(
              inputTokens = (u / "input_tokens").asLong.getOrElse(0L),
              cacheReadTokens = (u / "cache_read_input_tokens").asLong.getOrElse(0L),
              cacheWriteTokens = (u / "cache_creation_input_tokens").asLong.getOrElse(0L)
            )
          }
          ()
        case "content_block_start" =>
          val block = json / "content_block"
          (block / "type").asStr.getOrElse("") match
            case "text"     => blocks = blocks :+ Block.Text(new StringBuilder); ()
            case "thinking" => blocks = blocks :+ Block.Thinking(new StringBuilder, Absent); ()
            case "redacted_thinking" => blocks = blocks :+ Block.Thinking(new StringBuilder, Absent); ()
            case "tool_use" =>
              val id   = (block / "id").asStr.getOrElse("")
              val name = (block / "name").asStr.getOrElse("")
              blocks = blocks :+ Block.ToolUse(id, name, new StringBuilder)
              onEvent(StreamEvent.ToolUseStarted(id, name))
            case _ => ()
        case "content_block_delta" =>
          val delta = json / "delta"
          (delta / "type").asStr.getOrElse("") match
            case "text_delta" =>
              val text = (delta / "text").asStr.getOrElse("")
              blocks.lastOption match
                case Some(Block.Text(sb)) => sb ++= text
                case _                    => blocks = blocks :+ Block.Text(new StringBuilder(text))
              onEvent(StreamEvent.TextDelta(text))
            case "thinking_delta" =>
              val text = (delta / "thinking").asStr.getOrElse("")
              blocks.lastOption match
                case Some(Block.Thinking(sb, _)) => sb ++= text
                case _                           => blocks = blocks :+ Block.Thinking(new StringBuilder(text), Absent)
              onEvent(StreamEvent.ThinkingDelta(text))
            case "signature_delta" =>
              blocks.lastOption match
                case Some(t @ Block.Thinking(_, _)) =>
                  t.signature = Present(t.signature.getOrElse("") + (delta / "signature").asStr.getOrElse(""))
                case _ => ()
              ()
            case "input_json_delta" =>
              val part = (delta / "partial_json").asStr.getOrElse("")
              blocks.lastOption match
                case Some(Block.ToolUse(id, _, args)) =>
                  args ++= part
                  onEvent(StreamEvent.ToolUseArgsDelta(id, part))
                case _ => ()
            case _ => ()
        case "message_delta" =>
          (json / "delta" / "stop_reason").asStr.map(sr => stopReason = sr)
          (json / "usage" / "output_tokens").asLong.map(o => usage = usage.copy(outputTokens = o))
          ()
        case "error" =>
          val msg = (json / "error" / "message").asStr.getOrElse(Jx.render(json))
          streamError = Present(ProviderError.Protocol(s"in-stream provider error: $msg"))
          ()
        case _ => () // ping / message_stop / content_block_stop
    end process

    def finish(): Result[ProviderError, TurnResponse] =
      streamError match
        case Present(err) => Result.fail(err)
        case Absent =>
          val content = blocks.toList.flatMap {
            case Block.Text(sb) if sb.nonEmpty       => List(Content.Text(sb.result()))
            case Block.Text(_)                       => Nil
            case Block.Thinking(sb, sig)             => List(Content.Thinking(sb.result(), sig))
            case Block.ToolUse(id, name, args) =>
              List(Content.ToolUse(id, name, if args.isEmpty then "{}" else args.result()))
          }
          val hasTools = content.exists { case _: Content.ToolUse => true; case _ => false }
          Result.succeed(TurnResponse(Message(Role.Assistant, content), mapStop(stopReason, hasTools), usage))
  end StreamAccumulator

  // --- non-streaming ------------------------------------------------------

  private def parseComplete(json: Value): Result[ProviderError, TurnResponse] =
    (json / "content").asArr match
      case Absent =>
        Result.fail(ProviderError.Protocol(s"Anthropic response has no content: ${Jx.render(json).take(500)}"))
      case Present(items) =>
        val content = items.toList.flatMap { item =>
          (item / "type").asStr.getOrElse("") match
            case "text"     => (item / "text").asStr.map(Content.Text(_)).toList
            case "thinking" =>
              (item / "thinking").asStr.map(t => Content.Thinking(t, (item / "signature").asStr)).toList
            case "tool_use" =>
              (for
                id   <- (item / "id").asStr
                name <- (item / "name").asStr
              yield Content.ToolUse(id, name, (item / "input").map(Jx.render).getOrElse("{}"))).toList
            case _ => Nil
        }
        val u = json / "usage"
        val usage = Usage(
          inputTokens = (u / "input_tokens").asLong.getOrElse(0L),
          outputTokens = (u / "output_tokens").asLong.getOrElse(0L),
          cacheReadTokens = (u / "cache_read_input_tokens").asLong.getOrElse(0L),
          cacheWriteTokens = (u / "cache_creation_input_tokens").asLong.getOrElse(0L)
        )
        val hasTools = content.exists { case _: Content.ToolUse => true; case _ => false }
        Result.succeed(
          TurnResponse(
            Message(Role.Assistant, content),
            mapStop((json / "stop_reason").asStr.getOrElse(""), hasTools),
            usage
          )
        )

  private def mapStop(reason: String, hasTools: Boolean): StopReason =
    if hasTools then StopReason.ToolUse
    else
      reason match
        case "end_turn" | "stop_sequence"      => StopReason.EndTurn
        case "tool_use"                        => StopReason.ToolUse
        case "max_tokens"                      => StopReason.MaxTokens
        case "model_context_window_exceeded"   => StopReason.MaxTokens
        case "refusal"                         => StopReason.Refusal
        case _                                 => StopReason.Other
end AnthropicTransport

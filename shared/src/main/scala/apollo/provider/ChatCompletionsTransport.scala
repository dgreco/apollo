package apollo.provider

import apollo.core.*
import apollo.http.{HttpError, Transport}
import apollo.util.Jx.*
import apollo.util.{Jx, Sse}
import kyo.*
import kyo.Structure.Value

/** OpenAI chat-completions wire (`POST /chat/completions`) — the protocol of
  * OpenRouter, Nous, Groq, DeepSeek, GLM, Kimi and the rest of the
  * OpenAI-compatible fleet.
  */
object ChatCompletionsTransport extends WireTransport:

  /** Model families that take role `developer` instead of `system`. */
  private def developerRole(model: String): Boolean =
    val m = model.toLowerCase
    m.startsWith("gpt-5") || m.contains("codex") || m.startsWith("o1") || m.startsWith("o3") || m.startsWith("o4")

  def streamTurn(request: TurnRequest)(
      onEvent: StreamEvent => Unit < (Sync & Async)
  ): TurnResponse < (Sync & Async & Abort[HttpError]) =
    val rt   = request.runtime
    val url  = s"${rt.baseUrl}/chat/completions"
    val body = buildBody(request, stream = rt.streaming)
    if rt.streaming then
      val acc = new StreamAccumulator(onEvent)
      Transport.postSse(url, authHeaders(rt), Jx.render(body))(acc.onSse)
        .map(_ => Abort.get(acc.finish()))
    else
      Transport.postJson(url, authHeaders(rt), Jx.render(body)).map { text =>
        Jx.parse(text) match
          case Result.Success(json) => Abort.get(parseComplete(json))
          case Result.Failure(err)  => Abort.fail(HttpError.Protocol(s"unparseable response: $err"))
          case _                    => Abort.fail(HttpError.Protocol("unparseable response"))
      }
  end streamTurn

  private def authHeaders(rt: ResolvedRuntime): List[(String, String)] =
    rt.apiKey.map(k => ("authorization", s"Bearer $k")).toList ++ rt.headers.toList

  // --- request build ------------------------------------------------------

  private[provider] def buildBody(request: TurnRequest, stream: Boolean): Value =
    val rt = request.runtime
    val systemRole = if developerRole(rt.wireModel) then "developer" else "system"
    val wireMessages =
      Jx.arr(
        Jx.obj("role" -> Jx.str(systemRole), "content" -> Jx.str(request.systemPrompt))
          :: request.messages.flatMap(encodeMessage)
      )
    val toolsJson =
      if request.tools.isEmpty then Absent
      else
        Present(Jx.arr(request.tools.map { t =>
          Jx.obj(
            "type" -> Jx.str("function"),
            "function" -> Jx.obj(
              "name"        -> Jx.str(t.name),
              "description" -> Jx.str(t.description),
              "parameters"  -> Jx.parse(t.parametersJson).getOrElse(Jx.obj())
            )
          )
        }))

    val base = Jx.objOf(
      "model"    -> Present(Jx.str(rt.wireModel)),
      "messages" -> Present(wireMessages),
      "tools"    -> toolsJson,
      "stream"   -> (if stream then Present(Jx.bool(true)) else Absent),
      "stream_options" -> (if stream then Present(Jx.obj("include_usage" -> Jx.bool(true))) else Absent),
      "max_tokens" -> rt.maxTokens.map(Jx.num),
      "temperature" -> (rt.profile.map(_.temperature) match
        case Present(TemperaturePolicy.Fixed(v)) => Present(Jx.num(v))
        case _                                   => Absent)
    )
    applyReasoning(base, rt)
  end buildBody

  /** Reasoning wire shape per provider family. The Python SDK's `extra_body`
    * merges into the JSON root, so `extra_body.reasoning` is a root field
    * here too.
    */
  private def applyReasoning(body: Value, rt: ResolvedRuntime): Value =
    (rt.reasoning, rt.profile.map(_.reasoningStyle).getOrElse(ReasoningStyle.NoReasoning)) match
      case (Absent, _) => body // unset stays unset; never invent an effort
      case (Present(cfg), ReasoningStyle.ExtraBodyReasoning) =>
        body.withField("reasoning", Jx.obj("enabled" -> Jx.bool(cfg.enabled), "effort" -> Jx.str(cfg.effort)))
      case (Present(cfg), ReasoningStyle.TopLevelEffort(supported, overrides)) =>
        if !cfg.enabled then body.withField("reasoning_effort", Jx.str("none"))
        else body.withField("reasoning_effort", Jx.str(Reasoning.clamp(cfg.effort, supported, overrides)))
      case (Present(cfg), ReasoningStyle.ThinkingType) =>
        body.withField("thinking", Jx.obj("type" -> Jx.str(if cfg.enabled then "enabled" else "disabled")))
      case (Present(_), _) => body

  private def encodeMessage(msg: Message): List[Value] =
    msg.role match
      case Role.System =>
        List(Jx.obj("role" -> Jx.str("system"), "content" -> Jx.str(textOf(msg))))
      case Role.User =>
        val images = msg.content.collect { case img: Content.Image => img }
        if images.isEmpty then List(Jx.obj("role" -> Jx.str("user"), "content" -> Jx.str(textOf(msg))))
        else
          val parts =
            msg.content.collect {
              case Content.Text(t) => Jx.obj("type" -> Jx.str("text"), "text" -> Jx.str(t))
              case Content.Image(mediaType, b64) =>
                Jx.obj(
                  "type"      -> Jx.str("image_url"),
                  "image_url" -> Jx.obj("url" -> Jx.str(s"data:$mediaType;base64,$b64"))
                )
            }
          List(Jx.obj("role" -> Jx.str("user"), "content" -> Jx.arr(parts)))
      case Role.Assistant =>
        val toolUses = msg.content.collect { case tu: Content.ToolUse => tu }
        val reasoning = msg.content.collectFirst { case Content.Thinking(t, _) => t }
        val fields = List(
          "role"    -> Present(Jx.str("assistant")),
          "content" -> Present(Jx.str(textOf(msg))),
          "reasoning_content" -> Maybe.fromOption(reasoning).map(Jx.str),
          "tool_calls" ->
            (if toolUses.isEmpty then Absent
             else
               Present(Jx.arr(toolUses.map { tu =>
                 Jx.obj(
                   "id"   -> Jx.str(tu.id),
                   "type" -> Jx.str("function"),
                   "function" -> Jx.obj("name" -> Jx.str(tu.name), "arguments" -> Jx.str(tu.arguments))
                 )
               })))
        )
        List(Jx.objOf(fields*))
      case Role.Tool =>
        msg.content.collect { case tr: Content.ToolResult =>
          Jx.obj(
            "role"         -> Jx.str("tool"),
            "tool_call_id" -> Jx.str(tr.toolUseId),
            "content"      -> Jx.str(tr.output)
          )
        }
  end encodeMessage

  private def textOf(msg: Message): String =
    msg.content.collect { case Content.Text(t) => t }.mkString("\n")

  // --- response streaming -------------------------------------------------

  /** Accumulates streamed deltas into the final message. Sequential access
    * only (the SSE fold is sequential), so plain mutable state is safe.
    */
  private final class StreamAccumulator(onEvent: StreamEvent => Unit < (Sync & Async)):
    private val content        = new StringBuilder
    private val reasoning      = new StringBuilder
    private var finishReason   = ""
    private var usage          = Usage.zero
    private var toolCalls      = Vector.empty[ToolCallAcc]
    private var streamError    = Maybe.empty[HttpError]

    private final class ToolCallAcc(var id: String, var name: String):
      val args = new StringBuilder

    def onSse(event: Sse.Event): Unit < (Sync & Async) =
      val data = event.data.trim
      if data.isEmpty || data == "[DONE]" then ()
      else
        Jx.parse(data) match
          case Result.Success(json) => processChunk(json)
          case _                    => () // tolerate keep-alives / non-JSON frames
    end onSse

    private def processChunk(json: Value): Unit < (Sync & Async) =
      // Some providers (DeepInfra) report errors as in-stream chunks.
      (json / "error").map(e => (e / "message").asStr.getOrElse(Jx.render(e))) match
        case Present(err) =>
          streamError = Present(HttpError.Protocol(s"in-stream provider error: $err"))
          ()
        case Absent =>
          (json / "usage").map(u => usage = parseUsage(u))
          val choice = (json / "choices").asArr.flatMap(cs => Maybe.fromOption(cs.headOption))
          choice.flatMap(c => (c / "finish_reason").asStr).map(fr => finishReason = fr)
          val events: List[StreamEvent] =
            choice.map(c => c / "delta").map { d =>
              val text      = (d / "content").asStr.filter(_.nonEmpty)
              val thinkText = (d / "reasoning_content").asStr.orElse((d / "reasoning").asStr).filter(_.nonEmpty)
              text.map(t => content ++= t)
              thinkText.map(t => reasoning ++= t)
              val toolEvents = (d / "tool_calls").asArr.getOrElse(Chunk.empty).toList.flatMap(processToolDelta)
              text.map(StreamEvent.TextDelta(_)).toList
                ++ thinkText.map(StreamEvent.ThinkingDelta(_)).toList
                ++ toolEvents
            }.getOrElse(Nil)
          Kyo.foreachDiscard(events)(onEvent)
    end processChunk

    private def processToolDelta(tc: Value): List[StreamEvent] =
      val index = (tc / "index").asLong.map(_.toInt).getOrElse(toolCalls.size)
      while toolCalls.size <= index do toolCalls = toolCalls :+ new ToolCallAcc("", "")
      val acc     = toolCalls(index)
      val started = acc.id.isEmpty && (tc / "id").asStr.nonEmpty
      (tc / "id").asStr.foreach(id => acc.id = id)
      (tc / "function" / "name").asStr.foreach(n => if acc.name.isEmpty then acc.name = n else acc.name += n)
      val argDelta = (tc / "function" / "arguments").asStr.getOrElse("")
      acc.args ++= argDelta
      val startEvent = if started then List(StreamEvent.ToolUseStarted(acc.id, acc.name)) else Nil
      val argEvent   = if argDelta.nonEmpty then List(StreamEvent.ToolUseArgsDelta(acc.id, argDelta)) else Nil
      startEvent ++ argEvent

    def finish(): Result[HttpError, TurnResponse] =
      streamError match
        case Present(err) => Result.fail(err)
        case Absent =>
          val blocks =
            (if reasoning.nonEmpty then List(Content.Thinking(reasoning.result(), Absent)) else Nil)
              ++ (if content.nonEmpty then List(Content.Text(content.result())) else Nil)
              ++ toolCalls.toList.filter(_.name.nonEmpty).map { acc =>
                Content.ToolUse(acc.id, acc.name, if acc.args.isEmpty then "{}" else acc.args.result())
              }
          Result.succeed(
            TurnResponse(
              message = Message(Role.Assistant, blocks),
              stopReason = stopReasonOf(finishReason, toolCalls.nonEmpty),
              usage = usage
            )
          )
  end StreamAccumulator

  // --- non-streaming ------------------------------------------------------

  private def parseComplete(json: Value): Result[HttpError, TurnResponse] =
    (json / "choices").asArr.flatMap(cs => Maybe.fromOption(cs.headOption)) match
      case Absent =>
        Result.fail(HttpError.Protocol(s"response has no choices: ${Jx.render(json).take(500)}"))
      case Present(choice) =>
        val msg          = choice / "message"
        val text         = (msg / "content").asStr.getOrElse("")
        val reasoning    = (msg / "reasoning_content").asStr.orElse((msg / "reasoning").asStr)
        val finishReason = (choice / "finish_reason").asStr.getOrElse("")
        val toolUses = (msg / "tool_calls").asArr.getOrElse(Chunk.empty).toList.flatMap { tc =>
          for
            id   <- (tc / "id").asStr.toList
            name <- (tc / "function" / "name").asStr.toList
          yield Content.ToolUse(id, name, (tc / "function" / "arguments").asStr.getOrElse("{}"))
        }
        val blocks =
          reasoning.filter(_.nonEmpty).map(r => Content.Thinking(r, Absent)).toList
            ++ (if text.nonEmpty then List(Content.Text(text)) else Nil)
            ++ toolUses
        Result.succeed(
          TurnResponse(
            Message(Role.Assistant, blocks),
            stopReasonOf(finishReason, toolUses.nonEmpty),
            (json / "usage").map(parseUsage).getOrElse(Usage.zero)
          )
        )

  // --- shared parsing -----------------------------------------------------

  private def parseUsage(u: Value): Usage =
    Usage(
      inputTokens = (u / "prompt_tokens").asLong.getOrElse(0L),
      outputTokens = (u / "completion_tokens").asLong.getOrElse(0L),
      cacheReadTokens = (u / "prompt_tokens_details" / "cached_tokens").asLong
        .orElse((u / "prompt_cache_hit_tokens").asLong).getOrElse(0L),
      cacheWriteTokens = (u / "prompt_tokens_details" / "cache_write_tokens").asLong.getOrElse(0L),
      reasoningTokens = (u / "completion_tokens_details" / "reasoning_tokens").asLong.getOrElse(0L)
    )

  private def stopReasonOf(finishReason: String, hasToolCalls: Boolean): StopReason =
    if hasToolCalls then StopReason.ToolUse
    else
      finishReason match
        case "stop"           => StopReason.EndTurn
        case "tool_calls"     => StopReason.ToolUse
        case "length"         => StopReason.MaxTokens
        case "content_filter" => StopReason.Refusal
        case _                => StopReason.Other
end ChatCompletionsTransport

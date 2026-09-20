package apollo.provider

import apollo.core.*
import apollo.http.{HttpError, Transport}
import apollo.util.Jx.*
import apollo.util.{Jx, Sse}
import kyo.*
import kyo.Structure.Value

/** OpenAI Responses wire (`POST /responses`) — the native protocol of the
  * direct OpenAI API, xAI, Meta AI, Router and Actual.
  */
object ResponsesTransport extends WireTransport:

  def streamTurn(request: TurnRequest)(
      onEvent: StreamEvent => Unit < (Sync & Async)
  ): TurnResponse < (Sync & Async & Abort[HttpError]) =
    val rt  = request.runtime
    val url = s"${rt.baseUrl}/responses"
    if rt.streaming then
      val acc = new StreamAccumulator(onEvent)
      Transport.postSse(url, authHeaders(rt), Jx.render(buildBody(request, stream = true)))(acc.onSse)
        .map(_ => Abort.get(acc.finish()))
    else
      Transport.postJson(url, authHeaders(rt), Jx.render(buildBody(request, stream = false))).map { text =>
        Jx.parse(text) match
          case Result.Success(json) => Abort.get(parseResponseObject(json / "response" match
            case Present(inner) => inner
            case Absent         => json))
          case _ => Abort.fail(HttpError.Protocol("unparseable Responses payload"))
      }
  end streamTurn

  private def authHeaders(rt: ResolvedRuntime): List[(String, String)] =
    rt.apiKey.map(k => ("authorization", s"Bearer $k")).toList ++ rt.headers.toList

  // --- request build ------------------------------------------------------

  private[provider] def buildBody(request: TurnRequest, stream: Boolean): Value =
    val rt = request.runtime
    val toolsJson =
      if request.tools.isEmpty then Absent
      else
        Present(Jx.arr(request.tools.map { t =>
          Jx.obj(
            "type"        -> Jx.str("function"),
            "name"        -> Jx.str(t.name),
            "description" -> Jx.str(t.description),
            "parameters"  -> Jx.parse(t.parametersJson).getOrElse(Jx.obj()),
            "strict"      -> Jx.bool(false)
          )
        }))
    val reasoningField = rt.reasoning.filter(_.enabled).map { cfg =>
      val effort = Reasoning.clamp(cfg.effort, Seq("none", "low", "medium", "high", "xhigh", "max"))
      Jx.obj("effort" -> Jx.str(effort), "summary" -> Jx.str("auto"))
    }
    Jx.objOf(
      "model"        -> Present(Jx.str(rt.wireModel)),
      "instructions" -> Present(Jx.str(request.systemPrompt)),
      "input"        -> Present(Jx.arr(request.messages.flatMap(encodeItem))),
      "store"        -> Present(Jx.bool(false)),
      "stream"       -> (if stream then Present(Jx.bool(true)) else Absent),
      "tools"        -> toolsJson,
      "tool_choice"  -> toolsJson.map(_ => Jx.str("auto")),
      "parallel_tool_calls" -> toolsJson.map(_ => Jx.bool(true)),
      "reasoning"    -> reasoningField,
      "include"      -> reasoningField.map(_ => Jx.arr(List(Jx.str("reasoning.encrypted_content")))),
      "max_output_tokens" -> rt.maxTokens.map(Jx.num)
    )
  end buildBody

  private def encodeItem(msg: Message): List[Value] =
    msg.role match
      case Role.System => Nil // carried in `instructions`
      case Role.User =>
        val parts = msg.content.collect {
          case Content.Text(t) => Jx.obj("type" -> Jx.str("input_text"), "text" -> Jx.str(t))
          case Content.Image(mediaType, b64) =>
            Jx.obj("type" -> Jx.str("input_image"), "image_url" -> Jx.str(s"data:$mediaType;base64,$b64"))
        }
        if parts.isEmpty then Nil
        else List(Jx.obj("type" -> Jx.str("message"), "role" -> Jx.str("user"), "content" -> Jx.arr(parts)))
      case Role.Assistant =>
        val text = msg.content.collect { case Content.Text(t) if t.nonEmpty => t }
        val textItem =
          if text.isEmpty then Nil
          else
            List(Jx.obj(
              "type"    -> Jx.str("message"),
              "role"    -> Jx.str("assistant"),
              "content" -> Jx.arr(List(Jx.obj("type" -> Jx.str("output_text"), "text" -> Jx.str(text.mkString("\n")))))
            ))
        val callItems = msg.content.collect { case Content.ToolUse(id, name, arguments) =>
          Jx.obj(
            "type"      -> Jx.str("function_call"),
            "call_id"   -> Jx.str(id),
            "name"      -> Jx.str(name),
            "arguments" -> Jx.str(arguments)
          )
        }
        textItem ++ callItems
      case Role.Tool =>
        msg.content.collect { case Content.ToolResult(toolUseId, output, _) =>
          Jx.obj(
            "type"    -> Jx.str("function_call_output"),
            "call_id" -> Jx.str(toolUseId),
            "output"  -> Jx.str(output)
          )
        }
  end encodeItem

  // --- response streaming -------------------------------------------------

  private final class StreamAccumulator(onEvent: StreamEvent => Unit < (Sync & Async)):
    private val text        = new StringBuilder
    private val reasoning   = new StringBuilder
    private var calls       = Vector.empty[(String, String, StringBuilder)] // (call_id, name, args)
    private var byItemId    = Map.empty[String, Int]
    private var usage       = Usage.zero
    private var status      = ""
    private var streamError = Maybe.empty[HttpError]

    def onSse(event: Sse.Event): Unit < (Sync & Async) =
      val data = event.data.trim
      if data.isEmpty || data == "[DONE]" then ()
      else
        Jx.parse(data) match
          case Result.Success(json) => process(json)
          case _                    => ()

    private def process(json: Value): Unit < (Sync & Async) =
      (json / "type").asStr.getOrElse("") match
        case "response.output_text.delta" =>
          val delta = (json / "delta").asStr.getOrElse("")
          text ++= delta
          onEvent(StreamEvent.TextDelta(delta))
        case "response.reasoning_summary_text.delta" | "response.reasoning_text.delta" =>
          val delta = (json / "delta").asStr.getOrElse("")
          reasoning ++= delta
          onEvent(StreamEvent.ThinkingDelta(delta))
        case "response.output_item.added" =>
          val item = json / "item"
          if (item / "type").asStr.contains("function_call") then
            val callId = (item / "call_id").asStr.getOrElse("")
            val name   = (item / "name").asStr.getOrElse("")
            val itemId = (item / "id").asStr.getOrElse(callId)
            byItemId = byItemId.updated(itemId, calls.size)
            calls = calls :+ (callId, name, new StringBuilder((item / "arguments").asStr.getOrElse("")))
            onEvent(StreamEvent.ToolUseStarted(callId, name))
          else ()
        case "response.function_call_arguments.delta" =>
          val delta  = (json / "delta").asStr.getOrElse("")
          val itemId = (json / "item_id").asStr.getOrElse("")
          byItemId.get(itemId).orElse(Option.when(calls.nonEmpty)(calls.size - 1)) match
            case Some(idx) =>
              calls(idx)._3 ++= delta
              onEvent(StreamEvent.ToolUseArgsDelta(calls(idx)._1, delta))
            case None => ()
        case "response.output_item.done" =>
          // Arguments may arrive complete here; prefer the final form.
          val item = json / "item"
          if (item / "type").asStr.contains("function_call") then
            val itemId = (item / "id").asStr.orElse((item / "call_id").asStr).getOrElse("")
            (item / "arguments").asStr.filter(_.nonEmpty).map { args =>
              byItemId.get(itemId).foreach { idx =>
                calls(idx)._3.clear()
                calls(idx)._3 ++= args
              }
            }
            ()
          else ()
        case "response.completed" | "response.incomplete" | "response.failed" =>
          val resp = json / "response"
          status = (resp / "status").asStr.getOrElse("completed")
          (resp / "usage").map(u => usage = parseUsage(u))
          if status == "failed" then
            val msg = (resp / "error" / "message").asStr.getOrElse("response.failed")
            streamError = Present(HttpError.Protocol(msg))
          ()
        case "error" =>
          streamError = Present(
            HttpError.Protocol((json / "message").asStr.getOrElse(Jx.render(json).take(500)))
          )
          ()
        case _ => ()
    end process

    def finish(): Result[HttpError, TurnResponse] =
      streamError match
        case Present(err) => Result.fail(err)
        case Absent =>
          val blocks =
            (if reasoning.nonEmpty then List(Content.Thinking(reasoning.result(), Absent)) else Nil)
              ++ (if text.nonEmpty then List(Content.Text(text.result())) else Nil)
              ++ calls.toList.map((id, name, args) =>
                Content.ToolUse(id, name, if args.isEmpty then "{}" else args.result())
              )
          val stop =
            if calls.nonEmpty then StopReason.ToolUse
            else if status == "incomplete" then StopReason.MaxTokens
            else StopReason.EndTurn
          Result.succeed(TurnResponse(Message(Role.Assistant, blocks), stop, usage))
  end StreamAccumulator

  // --- non-streaming ------------------------------------------------------

  private def parseResponseObject(resp: Value): Result[HttpError, TurnResponse] =
    (resp / "output").asArr match
      case Absent =>
        Result.fail(HttpError.Protocol(s"Responses payload has no output: ${Jx.render(resp).take(500)}"))
      case Present(items) =>
        val texts = new StringBuilder
        val reasoningTexts = new StringBuilder
        var calls = List.empty[Content.ToolUse]
        items.foreach { item =>
          (item / "type").asStr.getOrElse("") match
            case "message" =>
              (item / "content").asArr.getOrElse(Chunk.empty).foreach { part =>
                if (part / "type").asStr.contains("output_text") then
                  texts ++= (part / "text").asStr.getOrElse("")
              }
            case "function_call" =>
              calls = calls :+ Content.ToolUse(
                (item / "call_id").asStr.getOrElse(""),
                (item / "name").asStr.getOrElse(""),
                (item / "arguments").asStr.getOrElse("{}")
              )
            case "reasoning" =>
              (item / "summary").asArr.getOrElse(Chunk.empty).foreach { s =>
                reasoningTexts ++= (s / "text").asStr.getOrElse("")
              }
            case _ => ()
        }
        val blocks =
          (if reasoningTexts.nonEmpty then List(Content.Thinking(reasoningTexts.result(), Absent)) else Nil)
            ++ (if texts.nonEmpty then List(Content.Text(texts.result())) else Nil)
            ++ calls
        val stop =
          if calls.nonEmpty then StopReason.ToolUse
          else if (resp / "status").asStr.contains("incomplete") then StopReason.MaxTokens
          else StopReason.EndTurn
        Result.succeed(
          TurnResponse(
            Message(Role.Assistant, blocks),
            stop,
            (resp / "usage").map(parseUsage).getOrElse(Usage.zero)
          )
        )

  private def parseUsage(u: Value): Usage =
    Usage(
      inputTokens = (u / "input_tokens").asLong.getOrElse(0L),
      outputTokens = (u / "output_tokens").asLong.getOrElse(0L),
      cacheReadTokens = (u / "input_tokens_details" / "cached_tokens").asLong.getOrElse(0L),
      reasoningTokens = (u / "output_tokens_details" / "reasoning_tokens").asLong.getOrElse(0L)
    )
end ResponsesTransport

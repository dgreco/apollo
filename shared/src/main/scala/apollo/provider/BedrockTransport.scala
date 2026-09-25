// SPDX-FileCopyrightText: 2026 David Greco <greco@acm.org>
//
// SPDX-License-Identifier: Apache-2.0

package apollo.provider

import apollo.core.*
import apollo.http.{HttpError, Transport}
import apollo.util.Jx
import apollo.util.Jx.*
import kyo.*
import kyo.Structure.Value

/** AWS Bedrock **Converse** wire (`POST /model/{modelId}/converse`), signed
  * with SigV4. Structurally close to the Anthropic Messages format —
  * content-block messages, `toolUse`/`toolResult`, a separate `system` — but
  * camelCase keys and AWS auth. Supports both `converse` (complete response) and
  * `converse-stream` (the binary `application/vnd.amazon.eventstream` codec,
  * decoded by `apollo.util.EventStream`), selected by `model.streaming`.
  *
  * AWS credentials + region arrive via private slots in `runtime.headers`
  * (see `Runtime.awsCreds`); they are consumed here and never sent as HTTP
  * headers.
  */
object BedrockTransport extends WireTransport:

  val hAccessKey  = "x-apollo-aws-access-key-id"
  val hSecretKey  = "x-apollo-aws-secret-access-key"
  val hSessionTok = "x-apollo-aws-session-token"
  val hRegion     = "x-apollo-aws-region"
  val hEndpoint   = "x-apollo-aws-endpoint" // test/self-hosted override

  private val defaultMaxTokens = 4096

  def streamTurn(request: TurnRequest)(
      onEvent: StreamEvent => Unit < (Sync & Async)
  ): TurnResponse < (Sync & Async & Abort[HttpError]) =
    val rt = request.runtime
    rt.headers.get(hAccessKey) match
      case None =>
        Abort.fail(HttpError.Protocol(
          "AWS Bedrock needs credentials: set AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY " +
            "(and AWS_REGION) in the environment or ~/.apollo/.env."))
      case Some(accessKey) =>
        val region   = rt.headers.getOrElse(hRegion, "us-east-1")
        val endpoint = rt.headers.getOrElse(hEndpoint,
          s"https://bedrock-runtime.$region.amazonaws.com")
        val modelId  = rt.wireModel
        val stream   = rt.streaming
        val verb     = if stream then "converse-stream" else "converse"
        val path     = s"/model/${encodePathSegment(modelId)}/$verb"
        val url      = s"${endpoint.stripSuffix("/")}$path"
        val body     = Jx.render(buildBody(request))
        val creds = SigV4.Credentials(
          accessKeyId = accessKey,
          secretAccessKey = rt.headers.getOrElse(hSecretKey, ""),
          sessionToken = rt.headers.get(hSessionTok)
        )
        val host = hostOf(url)
        signedHeadersV(creds, region, host, path, body).map { headers =>
          if stream then streamConverse(url, headers, body)(onEvent)
          else
            Transport.postJson(url, headers, body).map { text =>
              Jx.parse(text) match
                case Result.Success(json) =>
                  onEvent(StreamEvent.Done).andThen(Abort.get(parseComplete(json)))
                case _ => Abort.fail(HttpError.Protocol("unparseable Bedrock Converse response"))
            }
        }
  end streamTurn

  /** ConverseStream: decode the binary eventstream, emit deltas as they arrive,
    * and assemble the final response from the accumulated blocks. */
  private def streamConverse(url: String, headers: List[(String, String)], body: String)(
      onEvent: StreamEvent => Unit < (Sync & Async)
  ): TurnResponse < (Sync & Async & Abort[HttpError]) =
    val acc = new StreamAcc
    Transport.postEventStream(url, headers, body) { frame =>
      frame.messageType match
        case Some("exception") =>
          Sync.defer(acc.offerException(new String(frame.payload, "UTF-8")))
        case _ =>
          frame.eventType match
            case Some(evt) =>
              Jx.parse(new String(frame.payload, "UTF-8")) match
                case Result.Success(p) => Kyo.foreachDiscard(acc.offer(evt, p))(onEvent)
                case _                 => Sync.defer(())
            case None => Sync.defer(())
    }.andThen(onEvent(StreamEvent.Done)).andThen(Abort.get(acc.result))

  /** Accumulates ConverseStream events into one TurnResponse. Pure/testable:
    * `offer` folds an event and returns the deltas to surface. */
  private[provider] final class StreamAcc:
    private final class Block:
      var isTool      = false
      var toolId      = ""
      var toolName    = ""
      val input       = new StringBuilder
      var isReasoning = false
      val text        = new StringBuilder
    private val blocks = scala.collection.mutable.LinkedHashMap[Int, Block]()
    private var stopReason         = ""
    private var usage             = Usage.zero
    private var error: Maybe[String] = Absent

    def offerException(msg: String): Unit = error = Present(msg)

    def offer(eventType: String, p: Value): List[StreamEvent] =
      def block(idx: Int): Block = blocks.getOrElseUpdate(idx, new Block)
      def idxOf: Int = (p / "contentBlockIndex").asLong.getOrElse(0L).toInt
      eventType match
        case "contentBlockStart" =>
          val b = block(idxOf)
          (p / "start" / "toolUse") match
            case Present(tu) =>
              b.isTool = true
              b.toolId = (tu / "toolUseId").asStr.getOrElse("")
              b.toolName = (tu / "name").asStr.getOrElse("")
              List(StreamEvent.ToolUseStarted(b.toolId, b.toolName))
            case Absent => Nil
        case "contentBlockDelta" =>
          val b = block(idxOf)
          val d = p / "delta"
          (d / "text").asStr match
            case Present(t) => b.text.append(t); List(StreamEvent.TextDelta(t))
            case Absent =>
              (d / "toolUse" / "input").asStr match
                case Present(inp) => b.input.append(inp); List(StreamEvent.ToolUseArgsDelta(b.toolId, inp))
                case Absent =>
                  (d / "reasoningContent" / "text").asStr match
                    case Present(r) => b.isReasoning = true; b.text.append(r); List(StreamEvent.ThinkingDelta(r))
                    case Absent     => Nil
        case "messageStop" =>
          stopReason = (p / "stopReason").asStr.getOrElse(stopReason); Nil
        case "metadata" =>
          val u = p / "usage"
          usage = Usage(
            inputTokens = (u / "inputTokens").asLong.getOrElse(0L),
            outputTokens = (u / "outputTokens").asLong.getOrElse(0L))
          Nil
        case _ => Nil

    def result: Result[HttpError, TurnResponse] =
      error match
        case Present(m) => Result.fail(HttpError.Protocol(s"Bedrock stream error: $m"))
        case Absent =>
          val content = blocks.toList.sortBy(_._1).flatMap { (_, b) =>
            if b.isTool then
              List(Content.ToolUse(b.toolId, b.toolName, if b.input.isEmpty then "{}" else b.input.toString))
            else if b.isReasoning then List(Content.Thinking(b.text.toString, Absent))
            else if b.text.nonEmpty then List(Content.Text(b.text.toString))
            else Nil
          }
          val hasTools = content.exists { case _: Content.ToolUse => true; case _ => false }
          Result.succeed(TurnResponse(Message(Role.Assistant, content), mapStop(stopReason, hasTools), usage))
  end StreamAcc

  private def signedHeadersV(
      creds: SigV4.Credentials, region: String, host: String, path: String, body: String
  ): List[(String, String)] < Sync =
    Sync.defer {
      val amzDate = SigV4.amzDate(java.lang.System.currentTimeMillis())
      SigV4.signedHeaders(creds, region, "bedrock", host, path, body,
        contentType = "application/json", amzDate = amzDate)
    }

  // --- request build ------------------------------------------------------

  private[provider] def buildBody(request: TurnRequest): Value =
    val rt        = request.runtime
    val maxTokens = rt.maxTokens.getOrElse(defaultMaxTokens)
    val system =
      if request.systemPrompt.nonEmpty then
        Present(Jx.arr(Jx.obj("text" -> Jx.str(request.systemPrompt))))
      else Absent
    val toolConfig =
      if request.tools.isEmpty then Absent
      else
        Present(Jx.obj("tools" -> Jx.arr(request.tools.map { t =>
          Jx.obj("toolSpec" -> Jx.obj(
            "name"        -> Jx.str(t.name),
            "description" -> Jx.str(t.description),
            "inputSchema" -> Jx.obj("json" -> Jx.parse(t.parametersJson).getOrElse(Jx.obj()))
          ))
        })))
    val inference = Jx.objOf(
      "maxTokens"   -> Present(Jx.num(maxTokens)),
      "temperature" -> Absent
    )
    Jx.objOf(
      "messages"        -> Present(Jx.arr(mergeAlternating(request.messages.flatMap(encodeMessage)))),
      "system"          -> system,
      "inferenceConfig" -> Present(inference),
      "toolConfig"      -> toolConfig
    )
  end buildBody

  /** One internal message → zero or one Converse message ({role, content[]}). */
  private def encodeMessage(msg: Message): List[(String, List[Value])] =
    msg.role match
      case Role.System => Nil
      case Role.User =>
        val parts = msg.content.collect {
          case Content.Text(t) => Jx.obj("text" -> Jx.str(t))
          case Content.Image(mediaType, b64) =>
            Jx.obj("image" -> Jx.obj(
              "format" -> Jx.str(mediaType.stripPrefix("image/")),
              "source" -> Jx.obj("bytes" -> Jx.str(b64))
            ))
        }
        if parts.isEmpty then Nil else List("user" -> parts)
      case Role.Assistant =>
        val parts = msg.content.collect {
          case Content.Text(t) if t.nonEmpty => Jx.obj("text" -> Jx.str(t))
          case Content.ToolUse(id, name, arguments) =>
            Jx.obj("toolUse" -> Jx.obj(
              "toolUseId" -> Jx.str(id),
              "name"      -> Jx.str(name),
              "input"     -> Jx.parse(arguments).getOrElse(Jx.obj())
            ))
        }
        if parts.isEmpty then Nil else List("assistant" -> parts)
      case Role.Tool =>
        // Tool results ride in a user-role message (like Anthropic).
        val parts = msg.content.collect { case Content.ToolResult(toolUseId, output, isError) =>
          Jx.obj("toolResult" -> Jx.objOf(
            "toolUseId" -> Present(Jx.str(toolUseId)),
            "content"   -> Present(Jx.arr(Jx.obj("text" -> Jx.str(output)))),
            "status"    -> Present(Jx.str(if isError then "error" else "success"))
          ))
        }
        if parts.isEmpty then Nil else List("user" -> parts)

  /** Converse rejects consecutive same-role messages; merge their content
    * blocks (the same invariant the loop's Alternation repair assumes, but
    * enforced here in the wire shape too).
    */
  private def mergeAlternating(msgs: List[(String, List[Value])]): List[Value] =
    msgs.foldLeft(List.empty[(String, List[Value])]) { (acc, cur) =>
      acc.lastOption match
        case Some((role, blocks)) if role == cur._1 => acc.init :+ (role, blocks ++ cur._2)
        case _                                      => acc :+ cur
    }.map((role, blocks) => Jx.obj("role" -> Jx.str(role), "content" -> Jx.arr(blocks)))

  // --- response parse -----------------------------------------------------

  private[provider] def parseComplete(json: Value): Result[HttpError, TurnResponse] =
    (json / "output" / "message" / "content").asArr match
      case Absent =>
        (json / "message").asStr.orElse((json / "Message").asStr) match
          case Present(err) => Result.fail(HttpError.Protocol(s"Bedrock error: $err"))
          case Absent       => Result.fail(HttpError.Protocol(
            s"Bedrock response has no output message: ${Jx.render(json).take(400)}"))
      case Present(items) =>
        val content = items.toList.flatMap { item =>
          item.field("text").asStr.map(Content.Text(_)).toList
            ++ item.field("toolUse").toList.flatMap { tu =>
              (for
                id   <- (tu / "toolUseId").asStr
                name <- (tu / "name").asStr
              yield Content.ToolUse(id, name, (tu / "input").map(Jx.render).getOrElse("{}"))).toList
            }
            ++ item.field("reasoningContent").flatMap(_.field("reasoningText")).toList.flatMap { rt =>
              (rt / "text").asStr.map(t => Content.Thinking(t, (rt / "signature").asStr)).toList
            }
        }
        val u = json / "usage"
        val usage = Usage(
          inputTokens = (u / "inputTokens").asLong.getOrElse(0L),
          outputTokens = (u / "outputTokens").asLong.getOrElse(0L)
        )
        val hasTools = content.exists { case _: Content.ToolUse => true; case _ => false }
        Result.succeed(TurnResponse(
          Message(Role.Assistant, content),
          mapStop((json / "stopReason").asStr.getOrElse(""), hasTools),
          usage
        ))

  private def mapStop(reason: String, hasTools: Boolean): StopReason =
    if hasTools then StopReason.ToolUse
    else reason match
      case "end_turn" | "stop_sequence"       => StopReason.EndTurn
      case "tool_use"                         => StopReason.ToolUse
      case "max_tokens"                       => StopReason.MaxTokens
      case "guardrail_intervened" | "content_filtered" => StopReason.Refusal
      case _                                  => StopReason.Other

  // --- helpers ------------------------------------------------------------

  /** URL-encode a path segment, preserving the RFC 3986 unreserved set (so
    * the request path and the SigV4 canonical URI agree). The modelId's `:`
    * and other reserved chars become percent-escapes.
    */
  private[provider] def encodePathSegment(seg: String): String =
    val sb = new StringBuilder
    seg.getBytes("UTF-8").foreach { b =>
      val c = (b & 0xff).toChar
      if (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
        || c == '-' || c == '_' || c == '.' || c == '~'
      then sb.append(c)
      else sb.append(f"%%${b & 0xff}%02X")
    }
    sb.toString

  private def hostOf(url: String): String =
    try java.net.URI.create(url).getHost catch case _: Exception => ""
end BedrockTransport

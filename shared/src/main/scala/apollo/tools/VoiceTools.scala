package apollo.tools

import apollo.config.Fs
import apollo.provider.{ProviderError, Transport}
import apollo.util.Jx
import apollo.util.Jx.*
import kyo.*
import kyo.Structure.Value

/** Voice I/O tools mirroring Hermes's `text_to_speech` + transcription:
  *  - text_to_speech: synthesize speech to an audio file — via an
  *    OpenAI-compatible `/audio/speech` endpoint, the macOS `say` command, or a
  *    custom command (`tts.provider` = openai | say | command).
  *  - transcribe: speech-to-text via an OpenAI-compatible `/audio/transcriptions`
  *    multipart upload (`stt.*`). */
object VoiceTools:

  // --- pure helpers --------------------------------------------------------

  def speechBody(model: String, input: String, voice: String, format: String): String =
    Jx.render(Jx.obj(
      "model" -> Jx.str(model), "input" -> Jx.str(input),
      "voice" -> Jx.str(voice), "response_format" -> Jx.str(format)))

  def sayArgs(path: String, text: String): List[String] = List("say", "-o", path, text)

  /** A minimal multipart/form-data body: a `model` text field + a `file` part. */
  def multipartAudio(boundary: String, model: String, filename: String, file: Array[Byte]): Array[Byte] =
    val pre =
      s"--$boundary\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\n$model\r\n" +
        s"--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$filename\"\r\n" +
        "Content-Type: application/octet-stream\r\n\r\n"
    val post = s"\r\n--$boundary--\r\n"
    pre.getBytes("UTF-8") ++ file ++ post.getBytes("UTF-8")

  def parseTranscript(json: Value): Result[String, String] =
    (json / "text").asStr match
      case Present(t) => Result.succeed(t)
      case Absent     => Result.fail((json / "error" / "message").asStr.getOrElse("transcription response missing text"))

  private def audioExt(format: String): String = format match
    case "opus" => "ogg"; case "aac" => "aac"; case "flac" => "flac"; case "wav" => "wav"
    case "pcm"  => "pcm"; case _ => "mp3"

  // --- tools ---------------------------------------------------------------

  val entries: List[ToolEntry] = List(
    ToolEntry(
      name = "text_to_speech",
      toolset = "tts",
      description = "Synthesize speech from text and save it as an audio file in the working directory " +
        "(provider: openai /audio/speech, macOS `say`, or a custom command).",
      parametersJson = """{"type":"object","properties":{
        "text":{"type":"string","description":"Text to speak"},
        "voice":{"type":"string","description":"Voice name (openai provider)"},
        "filename":{"type":"string","description":"Output file name"}
      },"required":["text"]}""".replaceAll("\n\\s*", ""),
      emoji = "🔊",
      available = ctx => ctx.config.ttsApiKey.nonEmpty || ctx.config.ttsProvider == "say" || ctx.config.ttsCommand.nonEmpty,
      handler = tts
    ),
    ToolEntry(
      name = "transcribe",
      toolset = "stt",
      description = "Transcribe an audio file to text via an OpenAI-compatible /audio/transcriptions endpoint. " +
        "Requires stt.api_key (or OPENAI_API_KEY).",
      parametersJson = """{"type":"object","properties":{
        "file":{"type":"string","description":"Path to an audio file (mp3/wav/m4a/…)"}
      },"required":["file"]}""".replaceAll("\n\\s*", ""),
      emoji = "🎙️",
      available = _.config.sttApiKey.nonEmpty,
      handler = transcribe
    )
  )

  private def tts(args: Value, ctx: ToolContext): ToolOutcome < (Sync & Async) =
    (args / "text").asStr match
      case Absent => ToolOutcome.Error("missing required parameter: text")
      case Present(text) =>
        val cfg = ctx.config
        cfg.ttsProvider match
          case "say" =>
            val name = (args / "filename").asStr.getOrElse(s"speech-${java.util.UUID.randomUUID.toString.take(8)}.aiff")
            val path = ctx.cwd.resolve(name)
            Abort.run[CommandException](Command(sayArgs(path.toString, text)*).text).map {
              case Result.Success(_) => ToolOutcome.Ok(s"saved speech to $path")
              case Result.Failure(e) => ToolOutcome.Error(s"say failed: ${e.getMessage} (macOS only)")
              case Result.Panic(e)   => ToolOutcome.Error(s"say failed: ${String.valueOf(e.getMessage)}")
            }
          case "command" =>
            cfg.ttsCommand match
              case Absent => ToolOutcome.Error("tts.provider is 'command' but tts.command is not set")
              case Present(cmd) => // the command reads the text from $TTS_TEXT
                Abort.run[CommandException](Command("sh", "-c", cmd).envAppend(Map("TTS_TEXT" -> text)).text).map {
                  case Result.Success(_) => ToolOutcome.Ok("speech generated via command ($TTS_TEXT)")
                  case _                 => ToolOutcome.Error("tts command failed")
                }
          case _ => // openai / HTTP
            cfg.ttsApiKey match
              case Absent => ToolOutcome.Error("text_to_speech requires tts.api_key (or OPENAI_API_KEY)")
              case Present(key) =>
                val voice = (args / "voice").asStr.getOrElse(cfg.ttsVoice)
                val body  = speechBody(cfg.ttsModel, text, voice, cfg.ttsFormat)
                val url   = s"${cfg.ttsApiBase.stripSuffix("/")}/audio/speech"
                Abort.run[ProviderError](Transport.postJsonToBytes(url, List("authorization" -> s"Bearer $key"), body)).map {
                  case Result.Success(bytes) =>
                    val name = (args / "filename").asStr.getOrElse(
                      s"speech-${java.util.UUID.randomUUID.toString.take(8)}.${audioExt(cfg.ttsFormat)}")
                    val path = ctx.cwd.resolve(name)
                    Fs.writeBytes(path, bytes).andThen(ToolOutcome.Ok(s"saved speech (${bytes.length} bytes) to $path"))
                  case Result.Failure(e) => ToolOutcome.Error(s"tts failed: ${e.getMessage}")
                  case Result.Panic(e)   => ToolOutcome.Error(s"tts failed: ${String.valueOf(e.getMessage)}")
                }

  private def transcribe(args: Value, ctx: ToolContext): ToolOutcome < (Sync & Async) =
    ((args / "file").asStr, ctx.config.sttApiKey) match
      case (Absent, _) => ToolOutcome.Error("missing required parameter: file")
      case (_, Absent) => ToolOutcome.Error("transcribe requires stt.api_key (or OPENAI_API_KEY)")
      case (Present(file), Present(key)) =>
        val path = ctx.cwd.resolve(file)
        Abort.run[java.io.IOException](Sync.defer(java.nio.file.Files.readAllBytes(path))).map {
          case Result.Failure(_) => ToolOutcome.Error(s"cannot read audio file: $path")
          case Result.Panic(_)   => ToolOutcome.Error(s"cannot read audio file: $path")
          case Result.Success(bytes) =>
            val boundary = "apolloBoundary" + java.util.UUID.randomUUID.toString.replace("-", "")
            val fname    = path.getFileName.toString
            val bodyB    = multipartAudio(boundary, ctx.config.sttModel, fname, bytes)
            val url      = s"${ctx.config.sttApiBase.stripSuffix("/")}/audio/transcriptions"
            val headers  = List("authorization" -> s"Bearer $key",
              "content-type" -> s"multipart/form-data; boundary=$boundary")
            Abort.run[ProviderError](Transport.postBinary(url, headers, bodyB)).map {
              case Result.Success(resp) =>
                Jx.parse(resp) match
                  case Result.Success(j) => parseTranscript(j) match
                    case Result.Success(t) => ToolOutcome.Ok(t)
                    case Result.Failure(e) => ToolOutcome.Error(e)
                    case _                 => ToolOutcome.Error("transcription error")
                  case _ => ToolOutcome.Error("unparseable transcription response")
              case Result.Failure(e) => ToolOutcome.Error(s"transcription failed: ${e.getMessage}")
              case Result.Panic(e)   => ToolOutcome.Error(s"transcription failed: ${String.valueOf(e.getMessage)}")
            }
        }
end VoiceTools

package apollo.tools

import apollo.config.Fs
import apollo.http.{HttpError, Transport}
import apollo.util.Jx
import apollo.util.Jx.*
import kyo.*
import kyo.Structure.Value

/** image_generate — generate an image from a prompt via an OpenAI-compatible
  * `/images/generations` endpoint (config `image.*`, key falls back to
  * OPENAI_API_KEY), save it under the working dir, and return the path. */
object ImageGen:

  /** Request body (b64_json so we can save the bytes ourselves). */
  def buildBody(model: String, prompt: String, size: String, n: Int): String =
    Jx.render(Jx.obj(
      "model"           -> Jx.str(model),
      "prompt"          -> Jx.str(prompt),
      "n"               -> Jx.num(n),
      "size"            -> Jx.str(size),
      "response_format" -> Jx.str("b64_json")))

  /** First image's base64 payload, or a readable error. */
  def parseResponse(json: Value): Result[String, String] =
    (json / "data").asArr.getOrElse(Chunk.empty).headOption match
      case Some(d) =>
        (d / "b64_json").asStr match
          case Present(b64) => Result.succeed(b64)
          case Absent       => Result.fail((json / "error" / "message").asStr.getOrElse("image response missing b64_json"))
      case None => Result.fail((json / "error" / "message").asStr.getOrElse("image response has no data"))

  val entries: List[ToolEntry] = List(
    ToolEntry(
      name = "image_generate",
      toolset = "image_gen",
      description = "Generate an image from a text prompt and save it as a PNG in the working directory.",
      parametersJson = """{"type":"object","properties":{
        "prompt":{"type":"string","description":"What to generate"},
        "size":{"type":"string","description":"WxH, e.g. 1024x1024","default":"1024x1024"},
        "filename":{"type":"string","description":"Output file name (default image-<n>.png)"}
      },"required":["prompt"]}""".replaceAll("\n\\s*", ""),
      emoji = "🎨",
      available = _.config.imageApiKey.nonEmpty,
      handler = (args, ctx) =>
        ((args / "prompt").asStr, ctx.config.imageApiKey) match
          case (Absent, _) => ToolOutcome.Error("missing required parameter: prompt")
          case (_, Absent) => ToolOutcome.Error("image_generate requires an API key (image.api_key or OPENAI_API_KEY)")
          case (Present(prompt), Present(key)) =>
            val size = (args / "size").asStr.getOrElse("1024x1024")
            val url  = s"${ctx.config.imageApiBase.stripSuffix("/")}/images/generations"
            val body = buildBody(ctx.config.imageModel, prompt, size, 1)
            Abort.run[HttpError](Transport.postJson(url, List("authorization" -> s"Bearer $key"), body)).map {
              case Result.Success(resp) =>
                Jx.parse(resp) match
                  case Result.Success(j) =>
                    parseResponse(j) match
                      case Result.Success(b64) =>
                        val name = (args / "filename").asStr.getOrElse(s"image-${java.util.UUID.randomUUID.toString.take(8)}.png")
                        val path = ctx.cwd.resolve(name)
                        Fs.writeBytes(path, java.util.Base64.getDecoder.decode(b64)).andThen(ToolOutcome.Ok(s"saved image to $path"))
                      case Result.Failure(e) => ToolOutcome.Error(e)
                  case _ => ToolOutcome.Error("unparseable image response")
              case Result.Failure(e) => ToolOutcome.Error(s"image generation failed: ${e.getMessage}")
              case Result.Panic(e)   => ToolOutcome.Error(s"image generation failed: ${String.valueOf(e.getMessage)}")
            }
    )
  )
end ImageGen

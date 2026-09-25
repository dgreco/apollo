// SPDX-FileCopyrightText: 2026 David Greco <greco@acm.org>
//
// SPDX-License-Identifier: Apache-2.0

package apollo.tools

import apollo.config.Fs
import apollo.http.{HttpError, Transport}
import apollo.util.Jx
import apollo.util.Jx.*
import kyo.*
import kyo.Structure.Value

/** video_generate — generate a video from a prompt via an OpenAI-compatible
  * async video API (`video.*` config, key falls back to OPENAI_API_KEY):
  * submit a job, poll until it completes, download the MP4 into the working
  * directory, and return the path. Sora-shaped, HTTP-only. */
object VideoGen:

  /** Job-creation body. */
  def buildBody(model: String, prompt: String, seconds: Maybe[Int], size: Maybe[String]): String =
    Jx.render(Jx.objOf(
      "model"   -> Present(Jx.str(model)),
      "prompt"  -> Present(Jx.str(prompt)),
      "seconds" -> seconds.map(s => Jx.str(s.toString)),
      "size"    -> size.map(Jx.str)))

  /** The job id from a create/status response, or a readable error. */
  def parseId(json: Value): Result[String, String] =
    (json / "id").asStr match
      case Present(id) => Result.succeed(id)
      case Absent      => Result.fail((json / "error" / "message").asStr.getOrElse("video response missing id"))

  def status(json: Value): String = (json / "status").asStr.getOrElse("")

  def isCompleted(s: String): Boolean = s == "completed" || s == "succeeded"
  def isFailed(s: String): Boolean    = s == "failed" || s == "error" || s == "cancelled" || s == "canceled"
  def isTerminal(s: String): Boolean  = isCompleted(s) || isFailed(s)

  /** Where to download the finished video: an explicit URL in the status JSON
    * if present, else the conventional `{base}/videos/{id}/content`. */
  def contentUrl(json: Value, base: String, id: String): String =
    (json / "output" / "url").asStr
      .orElse((json / "url").asStr)
      .orElse((json / "content_url").asStr)
      .getOrElse(s"${base.stripSuffix("/")}/videos/$id/content")

  val entries: List[ToolEntry] = List(
    ToolEntry(
      name = "video_generate",
      toolset = "video_gen",
      description = "Generate a video from a text prompt (async: submit, wait, download) and save it as an MP4 " +
        "in the working directory. Requires video.api_key (or OPENAI_API_KEY).",
      parametersJson = """{"type":"object","properties":{
        "prompt":{"type":"string","description":"What to generate"},
        "seconds":{"type":"integer","description":"Clip length in seconds"},
        "size":{"type":"string","description":"WxH, e.g. 1280x720"},
        "filename":{"type":"string","description":"Output file name (default video-<n>.mp4)"}
      },"required":["prompt"]}""".replaceAll("\n\\s*", ""),
      emoji = "🎬",
      available = _.config.videoApiKey.nonEmpty,
      handler = handle
    )
  )

  private def handle(args: Value, ctx: ToolContext): ToolOutcome < (Sync & Async) =
    ((args / "prompt").asStr, ctx.config.videoApiKey) match
      case (Absent, _) => ToolOutcome.Error("missing required parameter: prompt")
      case (_, Absent) => ToolOutcome.Error("video_generate requires an API key (video.api_key or OPENAI_API_KEY)")
      case (Present(prompt), Present(key)) =>
        val base    = ctx.config.videoApiBase.stripSuffix("/")
        val auth    = List("authorization" -> s"Bearer $key")
        val seconds = (args / "seconds").asLong.map(_.toInt)
        val size    = (args / "size").asStr
        val body    = buildBody(ctx.config.videoModel, prompt, seconds, size)
        Abort.run[HttpError](Transport.postJson(s"$base/videos", auth, body)).map { submitted =>
          val out: ToolOutcome < (Sync & Async) = submitted match
            case Result.Failure(e) => ToolOutcome.Error(s"video submit failed: ${e.getMessage}")
            case Result.Panic(e)   => ToolOutcome.Error(s"video submit failed: ${String.valueOf(e.getMessage)}")
            case Result.Success(resp) =>
              Jx.parse(resp) match
                case Result.Success(j) => parseId(j) match
                  case Result.Success(id) => poll(id, base, auth, args, ctx, ctx.config.videoMaxPolls)
                  case Result.Failure(e)  => ToolOutcome.Error(e)
                  case _                  => ToolOutcome.Error("video submit error")
                case _ => ToolOutcome.Error("unparseable video submit response")
          out
        }

  private def poll(
      id: String, base: String, auth: List[(String, String)], args: Value, ctx: ToolContext, remaining: Int
  ): ToolOutcome < (Sync & Async) =
    if remaining <= 0 then ToolOutcome.Error(s"video generation timed out (id $id)")
    else
      Abort.run[HttpError](Transport.getJson(s"$base/videos/$id", auth, 30.seconds)).map { polled =>
        val out: ToolOutcome < (Sync & Async) = polled match
          case Result.Failure(e) => ToolOutcome.Error(s"video poll failed: ${e.getMessage}")
          case Result.Panic(e)   => ToolOutcome.Error(s"video poll failed: ${String.valueOf(e.getMessage)}")
          case Result.Success(body) =>
            val json = Jx.parse(body).getOrElse(Jx.obj())
            val st   = status(json)
            if isCompleted(st) then download(contentUrl(json, base, id), auth, args, ctx)
            else if isFailed(st) then
              ToolOutcome.Error(s"video generation $st: ${(json / "error" / "message").asStr.getOrElse(id)}")
            else
              Async.sleep(ctx.config.videoPollSeconds.seconds)
                .andThen(poll(id, base, auth, args, ctx, remaining - 1))
        out
      }

  private def download(url: String, auth: List[(String, String)], args: Value, ctx: ToolContext): ToolOutcome < (Sync & Async) =
    Abort.run[HttpError](Transport.getBytes(url, auth, 5.minutes)).map {
      case Result.Success(bytes) =>
        val name = (args / "filename").asStr.getOrElse(s"video-${java.util.UUID.randomUUID.toString.take(8)}.mp4")
        val path = ctx.cwd.resolve(name)
        Fs.writeBytes(path, bytes).andThen(ToolOutcome.Ok(s"saved video (${bytes.length} bytes) to $path"))
      case Result.Failure(e) => ToolOutcome.Error(s"video download failed: ${e.getMessage}")
      case Result.Panic(e)   => ToolOutcome.Error(s"video download failed: ${String.valueOf(e.getMessage)}")
    }
end VideoGen

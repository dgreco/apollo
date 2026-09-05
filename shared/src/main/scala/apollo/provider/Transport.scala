package apollo.provider

import apollo.util.{Sse, Utf8}
import kyo.*

/** HTTP plumbing shared by every wire transport: POST a JSON body, consume
  * the response either as one text document or as a live SSE stream.
  *
  * Streaming is deliberately hand-parsed from raw bytes (rather than
  * kyo-http's typed SSE decoding) so that non-2xx error bodies — which are
  * plain JSON, not event-stream — surface as `ProviderError.Http` with the
  * provider's own message, and so partial UTF-8 sequences split across
  * chunks decode correctly.
  *
  * kyo-http's `sendWith` pins the response callback to `Abort[HttpException]`,
  * so provider-level failures travel out of the callback as `Result` values
  * and are lifted into `Abort[ProviderError]` afterwards.
  */
object Transport:

  private val streamRoute = HttpRoute.postRaw("").request(_.bodyText).response(_.bodyStream)
  private val textRoute   = HttpRoute.postRaw("").request(_.bodyText).response(_.bodyText)
  private val patchRoute  = HttpRoute.patchRaw("").request(_.bodyText).response(_.bodyText)
  private val getRoute    = HttpRoute.getRaw("").response(_.bodyText)

  /** Base headers every provider request carries unless overridden. */
  private val baseHeaders = List("content-type" -> "application/json", "accept" -> "application/json")

  /** kyo-http's client `timeout` caps the ENTIRE request lifecycle and
    * defaults to 5 seconds — far too short for model turns and long polls.
    * Every entry point below sets an explicit budget instead.
    */
  private val turnTimeout = 30.minutes

  /** POSTs `body` and feeds each SSE event to `onEvent` as it arrives. */
  def postSse(
      url: String,
      headers: List[(String, String)],
      body: String,
      timeout: Duration = turnTimeout
  )(onEvent: Sse.Event => Unit < (Sync & Async)): Unit < (Sync & Async & Abort[ProviderError]) =
    withRequest(url, (baseHeaders :+ ("accept" -> "text/event-stream")) ++ headers, body) { req =>
      liftResult {
        HttpClient.withConfig(_.timeout(timeout)) {
          HttpClient.use { client =>
          client.sendWith(streamRoute, req) { resp =>
            if !resp.status.isSuccess then
              collectBytes(resp.fields.body).map { bytes =>
                Result.fail(ProviderError.Http(resp.status.code, new String(bytes, "UTF-8")))
              }
            else
              // Sequential fold over the byte stream; parser state is local.
              var pending  = Array.emptyByteArray
              var sseState = Sse.State.empty
              resp.fields.body
                .foreach { span =>
                  val (text, rest) = Utf8.decodePrefix(pending ++ span.toArray)
                  pending = rest
                  val (events, nextState) = Sse.feed(sseState, text)
                  sseState = nextState
                  Kyo.foreachDiscard(events)(onEvent)
                }
                .map { _ =>
                  Kyo.foreachDiscard(Sse.flush(sseState))(onEvent).map(_ => Result.succeed(()))
                }
          }
          }
        }
      }
    }

  /** POSTs `body`, returns the full response text. */
  def postJson(
      url: String,
      headers: List[(String, String)],
      body: String,
      timeout: Duration = turnTimeout
  ): String < (Sync & Async & Abort[ProviderError]) =
    withRequest(url, baseHeaders ++ headers, body) { req =>
      liftResult {
        HttpClient.withConfig(_.timeout(timeout)) {
          HttpClient.use { client =>
          client.sendWith(textRoute, req) { resp =>
            val text = resp.fields.body
            if resp.status.isSuccess then Result.succeed(text)
            else Result.fail(ProviderError.Http(resp.status.code, text))
          }
          }
        }
      }
    }

  /** PATCHes `body`, returns the full response text (e.g. Discord message edits). */
  def patchJson(
      url: String,
      headers: List[(String, String)],
      body: String,
      timeout: Duration = turnTimeout
  ): String < (Sync & Async & Abort[ProviderError]) =
    HttpRequest.patchRaw(url) match
      case Result.Success(base) =>
        val withHeaders = (baseHeaders ++ headers).foldLeft(base)((r, h) => r.setHeader(h._1, h._2))
        val req = withHeaders.addField("body", body)
        liftResult {
          HttpClient.withConfig(_.timeout(timeout)) {
            HttpClient.use { client =>
              client.sendWith(patchRoute, req) { resp =>
                val text = resp.fields.body
                if resp.status.isSuccess then Result.succeed(text)
                else Result.fail(ProviderError.Http(resp.status.code, text))
              }
            }
          }
        }
      case _ => Abort.fail(ProviderError.Network(s"invalid URL: $url"))

  /** PUT with an empty body (e.g. Discord reaction adds). */
  def putEmpty(
      url: String,
      headers: List[(String, String)],
      timeout: Duration = 15.seconds
  ): Unit < (Sync & Async & Abort[ProviderError]) =
    emptyBodied(HttpRequest.putRaw(url), url, headers, timeout)

  /** DELETE with an empty body (e.g. Discord reaction removes). */
  def deleteEmpty(
      url: String,
      headers: List[(String, String)],
      timeout: Duration = 15.seconds
  ): Unit < (Sync & Async & Abort[ProviderError]) =
    emptyBodied(HttpRequest.deleteRaw(url), url, headers, timeout)

  private val emptyRoute = HttpRoute.getRaw("").response(_.bodyText)

  private def emptyBodied(
      parsed: Result[HttpException, HttpRequest[Any]],
      url: String,
      headers: List[(String, String)],
      timeout: Duration
  ): Unit < (Sync & Async & Abort[ProviderError]) =
    parsed match
      case Result.Success(base) =>
        val req = headers.foldLeft(base)((r, h) => r.setHeader(h._1, h._2))
        liftResult {
          HttpClient.withConfig(_.timeout(timeout)) {
            HttpClient.use { client =>
              client.sendWith(emptyRoute, req) { resp =>
                if resp.status.isSuccess then Result.succeed(())
                else Result.fail(ProviderError.Http(resp.status.code, resp.fields.body))
              }
            }
          }
        }
      case _ => Abort.fail(ProviderError.Network(s"invalid URL: $url"))

  /** GET returning the response text (catalogs, health checks, page
    * fetches, long polls — callers with a longer server-side hold pass
    * their own budget).
    */
  def getJson(
      url: String,
      headers: List[(String, String)],
      timeout: Duration = 30.seconds
  ): String < (Sync & Async & Abort[ProviderError]) =
    HttpRequest.getRaw(url) match
      case Result.Success(base) =>
        val req = headers.foldLeft(base)((r, h) => r.setHeader(h._1, h._2))
        liftResult {
          HttpClient.withConfig(_.timeout(timeout)) {
            HttpClient.use { client =>
            client.sendWith(getRoute, req) { resp =>
              val text = resp.fields.body
              if resp.status.isSuccess then Result.succeed(text)
              else Result.fail(ProviderError.Http(resp.status.code, text))
            }
            }
          }
        }
      case _ => Abort.fail(ProviderError.Network(s"invalid URL: $url"))

  // -----------------------------------------------------------------------

  private def withRequest[A](url: String, headers: List[(String, String)], body: String)(
      f: HttpRequest["body" ~ String] => A < (Sync & Async & Abort[ProviderError])
  ): A < (Sync & Async & Abort[ProviderError]) =
    HttpRequest.postRaw(url) match
      case Result.Success(base) =>
        val withHeaders = headers.foldLeft(base)((r, h) => r.setHeader(h._1, h._2))
        f(withHeaders.addField("body", body))
      case _ =>
        Abort.fail(ProviderError.Network(s"invalid URL: $url"))

  private def collectBytes(
      stream: Stream[Span[Byte], Async]
  ): Array[Byte] < (Sync & Async) =
    stream.fold(Array.emptyByteArray)((acc, span) => acc ++ span.toArray)

  /** Converts transport-level `HttpException` aborts to `ProviderError` and
    * lifts callback-carried `Result` failures into the effect channel.
    */
  private def liftResult[A](
      v: Result[ProviderError, A] < (Sync & Async & Abort[HttpException])
  ): A < (Sync & Async & Abort[ProviderError]) =
    Abort.run[HttpException](v).map {
      case Result.Success(r)     => Abort.get(r)
      case Result.Failure(e)     => Abort.fail(ProviderError.Network(e.getMessage))
      case Result.Panic(e)       => Abort.panic(e)
    }
end Transport

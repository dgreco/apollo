package apollo.mcp

import apollo.util.{Jx, Sse, Utf8}
import apollo.util.Jx.*
import kyo.*
import kyo.Structure.Value

/** One connected MCP server over the streamable-HTTP transport
  * (2025-03-26+): each JSON-RPC message is POSTed to the server URL; the
  * response arrives as `application/json` (single message) or
  * `text/event-stream` (SSE events carrying messages, closed by a compliant
  * server once the matching response is delivered). `Mcp-Session-Id` from
  * `initialize` is echoed on every later request and DELETEd on close; the
  * `mcp-protocol-version` header carries the negotiated version, with
  * configured headers overriding any default (the upstream seeding rule).
  * The legacy 2024-11-05 HTTP+SSE transport (`transport: sse`) is not
  * implemented.
  */
final class McpHttpClient private (
    val serverName: String,
    url: String,
    configHeaders: List[(String, String)],
    defaultTimeout: Duration,
    tokenSource: Maybe[McpOAuth.TokenSource]
) extends McpConnection:

  private val nextId = new java.util.concurrent.atomic.AtomicLong(0L)
  @volatile private var initResult: Value            = Value.Null
  @volatile private var sessionId: Maybe[String]     = Absent
  @volatile private var negotiatedVersion: String    = Mcp.protocolVersion
  @volatile private var deadReason: Maybe[String]    = Absent

  /** Server→client JSON-RPC requests (`sampling/createMessage`,
    * `elicitation/create`) that arrive on an SSE stream are routed here; the
    * default refuses. `McpManager` wires it to the shared handlers. */
  @volatile private[mcp] var onServerRequest: (String, Value) => Result[String, Value] < (Sync & Async) =
    (m, _) => Result.fail(s"method not supported: $m")

  def initializeResult: Value = initResult
  def alive: Boolean          = deadReason.isEmpty
  def stdio: Boolean          = false

  def request(
      method: String,
      params: Maybe[Value],
      timeout: Maybe[Duration]
  ): Result[String, Value] < (Sync & Async) =
    deadReason match
      case Present(reason) => Result.fail(s"MCP server '$serverName' is not connected: $reason")
      case Absent =>
        val id = nextId.incrementAndGet()
        val payload = Jx.objOf(
          "jsonrpc" -> Present(Jx.str("2.0")),
          "id"      -> Present(Jx.num(id)),
          "method"  -> Present(Jx.str(method)),
          "params"  -> params
        )
        post(Jx.render(payload), timeout.getOrElse(defaultTimeout), Present(id)).map {
          case Result.Failure(err)    => Result.fail(err)
          case Result.Panic(e)        => Result.fail(String.valueOf(e.getMessage))
          case Result.Success(Absent) => Result.fail(s"MCP server '$serverName': no response to $method")
          case Result.Success(Present(msg)) =>
            msg / "error" match
              case Present(err) =>
                val code    = (err / "code").asLong.getOrElse(0L)
                val message = (err / "message").asStr.getOrElse("unknown error")
                Result.fail(s"MCP server '$serverName' error $code: $message")
              case Absent =>
                Result.succeed((msg / "result").getOrElse(Jx.obj()))
        }

  /** Notifications POST fire-and-forget (a compliant server answers 202);
    * failures are swallowed — they must never break a turn.
    */
  def notify(method: String, params: Maybe[Value]): Unit < (Sync & Async) =
    val payload = Jx.objOf(
      "jsonrpc" -> Present(Jx.str("2.0")),
      "method"  -> Present(Jx.str(method)),
      "params"  -> params
    )
    post(Jx.render(payload), defaultTimeout, Absent).unit

  /** Best-effort session DELETE (the spec's termination request), then dead. */
  def close: Unit < (Sync & Async) =
    val terminate: Unit < (Sync & Async) = sessionId match
      case Absent => ()
      case Present(sid) =>
        HttpRequest.deleteRaw(url) match
          case Result.Success(base) =>
            resolveBearer.map { bearer =>
              val req = withHeaders(base, bearer)
              Abort.run[HttpException] {
                HttpClient.withConfig(_.timeout(5.seconds)) {
                  HttpClient.use(_.sendWith(McpHttpClient.textRoute, req)(_ => ()))
                }
              }.unit
            }
          case _ => ()
    terminate.andThen(Sync.defer { deadReason = Present("closed") })

  def destroyNow(): Unit = deadReason = Present("shutdown")

  // --- HTTP core -----------------------------------------------------------

  /** Default headers first, then the OAuth bearer, then configured headers —
    * `setHeader` replaces, so a user-supplied `Authorization` (static header
    * auth) overrides the OAuth token, and OAuth overrides nothing the user
    * pinned; both override the defaults (the upstream seeding rule).
    */
  private def withHeaders[F](base: HttpRequest[F], authHeader: Maybe[String]): HttpRequest[F] =
    val defaults = List(
      "content-type"         -> "application/json",
      "accept"               -> "application/json, text/event-stream",
      "mcp-protocol-version" -> negotiatedVersion
    ) ++ sessionId.map(sid => List("mcp-session-id" -> sid)).getOrElse(Nil)
    val auth = authHeader.map(t => List("authorization" -> s"Bearer $t")).getOrElse(Nil)
    (defaults ++ auth ++ configHeaders).foldLeft(base)((r, h) => r.setHeader(h._1, h._2))

  /** POSTs one message, injecting the current OAuth bearer (if any) and, on
    * a 401 from an OAuth server, refreshing the token once and retrying.
    * `Absent` result = accepted without a body (202); `expectId` selects the
    * response message out of an SSE stream.
    */
  private[mcp] def post(
      body: String,
      timeout: Duration,
      expectId: Maybe[Long]
  ): Result[String, Maybe[Value]] < (Sync & Async) =
    resolveBearer.map { bearer =>
      postOnce(body, timeout, expectId, bearer).map {
        case Result.Failure(err) if McpHttpClient.isUnauthorized(err) && tokenSource.nonEmpty =>
          tokenSource.get.refreshNow.map {
            case Result.Success(fresh) => postOnce(body, timeout, expectId, Present(fresh))
            case _                     => Result.fail(err)
          }
        case other => other
      }
    }

  /** The current bearer for a request: Absent for non-OAuth servers, or when
    * the token lookup fails (the request then goes out unauthenticated and
    * the server's 401 drives re-auth guidance).
    */
  private def resolveBearer: Maybe[String] < (Sync & Async) =
    tokenSource match
      case Absent    => Absent
      case Present(ts) => ts.bearer.map {
        case Result.Success(tok) => Present(tok)
        case _                   => Absent
      }

  private def postOnce(
      body: String,
      timeout: Duration,
      expectId: Maybe[Long],
      bearer: Maybe[String]
  ): Result[String, Maybe[Value]] < (Sync & Async) =
    HttpRequest.postRaw(url) match
      case Result.Failure(e) => Result.fail(s"MCP server '$serverName': invalid URL $url (${e.getMessage})")
      case Result.Panic(e)   => Result.fail(s"MCP server '$serverName': invalid URL $url")
      case Result.Success(base) =>
        val req = withHeaders(base, bearer).addField("body", body)
        Abort.run[HttpException] {
          HttpClient.withConfig(_.timeout(timeout)) {
            HttpClient.use { client =>
              client.sendWith(McpHttpClient.streamRoute, req) { resp =>
                resp.headers.get("mcp-session-id") match
                  case Present(sid) => sessionId = Present(sid)
                  case Absent       => ()
                if resp.status.code == 202 then
                  (Result.succeed(Absent): Result[String, Maybe[Value]])
                else if !resp.status.isSuccess then
                  collectText(resp.fields.body).map { text =>
                    Result.fail(s"MCP server '$serverName': HTTP ${resp.status.code}" +
                      (if text.isBlank then "" else s" — ${text.take(300)}"))
                  }
                else
                  val contentType = resp.headers.get("content-type").getOrElse("")
                    .split(';').head.trim.toLowerCase
                  contentType match
                    case "application/json" =>
                      collectText(resp.fields.body).map(parseMessage)
                    case "text/event-stream" =>
                      readSse(resp.fields.body, expectId)
                    case other =>
                      // NonMcpEndpointError parity: a 2xx that isn't JSON or
                      // SSE is not an MCP endpoint.
                      collectText(resp.fields.body).map { _ =>
                        Result.fail(s"MCP server '$serverName': endpoint does not speak MCP " +
                          s"(content-type: ${if other.isEmpty then "none" else other})")
                      }
              }
            }
          }
        }.map {
          case Result.Success(r) => r
          case Result.Failure(e) => Result.fail(s"MCP server '$serverName': ${e.getMessage}")
          case Result.Panic(e)   => Result.fail(s"MCP server '$serverName': ${String.valueOf(e.getMessage)}")
        }
  end postOnce

  private def parseMessage(text: String): Result[String, Maybe[Value]] =
    Jx.parse(text) match
      case Result.Success(msg) => Result.succeed(Present(msg))
      case _ => Result.fail(s"MCP server '$serverName': unparseable JSON response (${text.take(200)})")

  /** Folds the SSE stream, keeping the message whose id matches our request.
    * Server→client *requests* (a message carrying both `method` and `id` —
    * `sampling/createMessage`, `elicitation/create`) are dispatched to
    * `onServerRequest` and their reply POSTed back on a fiber, so the fold
    * keeps draining while the server waits for our answer before completing
    * our original request. Server notifications (method, no id) are ignored.
    * A compliant server closes the stream after the response, so the fold
    * terminates; the request timeout bounds it regardless.
    */
  private def readSse(
      stream: Stream[Span[Byte], Async],
      expectId: Maybe[Long]
  ): Result[String, Maybe[Value]] < (Sync & Async) =
    var pending  = Array.emptyByteArray
    var sseState = Sse.State.empty
    var found: Maybe[Value] = Absent
    def handle(events: List[Sse.Event]): Unit < (Sync & Async) =
      Kyo.foreachDiscard(events) { e =>
        Jx.parse(e.data) match
          case Result.Success(msg) =>
            ((msg / "method").asStr, (msg / "id").asLong) match
              case (Present(m), Present(rid)) =>
                Fiber.initUnscoped(handleServerRequest(m, (msg / "params").getOrElse(Jx.obj()), rid)).unit
              case (Present(_), Absent) => Sync.defer(()) // notification: ignore
              case (Absent, msgId) =>
                if found.isEmpty && msgId == expectId then found = Present(msg)
                Sync.defer(())
          case _ => Sync.defer(())
      }
    stream
      .foreach { span =>
        val (text, rest) = Utf8.decodePrefix(pending ++ span.toArray)
        pending = rest
        val (events, next) = Sse.feed(sseState, text)
        sseState = next
        handle(events)
      }
      .map { _ =>
        handle(Sse.flush(sseState)).map { _ =>
          found match
            case Present(msg) => Result.succeed(Present(msg))
            case Absent =>
              if expectId.isEmpty then Result.succeed(Absent)
              else Result.fail(s"MCP server '$serverName': SSE stream ended without a response")
        }
      }
  end readSse

  /** Runs a server→client request through `onServerRequest` and POSTs the
    * JSON-RPC reply back to the endpoint (fire-and-forget; a compliant server
    * answers 202). */
  private def handleServerRequest(method: String, params: Value, rid: Long): Unit < (Sync & Async) =
    onServerRequest(method, params).map { result =>
      val reply = result match
        case Result.Success(v)   => jsonrpcResponse(rid, Present(v), Absent)
        case Result.Failure(msg) => jsonrpcResponse(rid, Absent, Present((-32603L, msg)))
        case Result.Panic(e)     => jsonrpcResponse(rid, Absent, Present((-32603L, String.valueOf(e.getMessage))))
      post(Jx.render(reply), defaultTimeout, Absent).unit
    }

  private def jsonrpcResponse(id: Long, result: Maybe[Value], error: Maybe[(Long, String)]): Value =
    Jx.objOf(
      "jsonrpc" -> Present(Jx.str("2.0")),
      "id"      -> Present(Jx.num(id)),
      "result"  -> result,
      "error"   -> error.map((code, msg) => Jx.obj("code" -> Jx.num(code), "message" -> Jx.str(msg)))
    )

  private def collectText(stream: Stream[Span[Byte], Async]): String < (Sync & Async) =
    stream.fold(Array.emptyByteArray)((acc, span) => acc ++ span.toArray)
      .map(bytes => new String(bytes, "UTF-8"))
end McpHttpClient

object McpHttpClient:

  private val streamRoute = HttpRoute.postRaw("").request(_.bodyText).response(_.bodyStream)
  private val textRoute   = HttpRoute.deleteRaw("").response(_.bodyText)

  /** A 401 in either our own message shape or kyo-http's HttpException
    * phrasing ("returned 401 (Unauthorized)").
    */
  private[mcp] def isUnauthorized(err: String): Boolean =
    err.contains("401") || err.toLowerCase.contains("unauthorized")

  /** Upstream `_validate_remote_mcp_url`: http(s) with a non-empty host. */
  def validateUrl(url: String): Maybe[String] =
    try
      val uri = java.net.URI.create(url)
      val scheme = Option(uri.getScheme).map(_.toLowerCase).getOrElse("")
      if scheme != "http" && scheme != "https" then
        Present(s"invalid MCP url '$url': scheme must be http or https")
      else if Option(uri.getHost).forall(_.isEmpty) then
        Present(s"invalid MCP url '$url': missing host")
      else Absent
    catch case _: Exception => Present(s"invalid MCP url '$url'")

  /** Connects: `initialize` → capture session id + negotiated version →
    * `notifications/initialized`.
    */
  def connect(
      serverName: String,
      url: String,
      headers: List[(String, String)],
      requestTimeout: Duration,
      clientVersion: String,
      tokenSource: Maybe[McpOAuth.TokenSource] = Absent
  ): Result[String, McpHttpClient] < (Sync & Async) =
    validateUrl(url) match
      case Present(err) => Result.fail(s"MCP server '$serverName': $err")
      case Absent =>
        val client = new McpHttpClient(serverName, url, headers, requestTimeout, tokenSource)
        client.request("initialize", Present(Mcp.initializeParams(clientVersion)), Absent).map {
          case Result.Failure(err) => Result.fail(err)
          case Result.Panic(e)     => Result.fail(String.valueOf(e.getMessage))
          case Result.Success(init) =>
            client.initResult = init
            (init / "protocolVersion").asStr match
              case Present(v) => client.negotiatedVersion = v
              case Absent     => ()
            client.notify("notifications/initialized", Absent)
              .andThen(Result.succeed(client))
        }
end McpHttpClient
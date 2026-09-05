package apollo.mcp

import kyo.*

/** The loopback OAuth redirect listener: a minimal raw-socket HTTP server
  * bound to 127.0.0.1 that accepts one `GET /callback?...` request, captures
  * its query parameters, and shows the browser a done page. A raw socket
  * (rather than the kyo-http server) keeps query parsing fully under our
  * control and portable across JVM and Native.
  */
final class CallbackServer private (
    socket: java.net.ServerSocket,
    promise: Promise[Map[String, String], Any]
):
  def port: Int = socket.getLocalPort

  /** Completes with the captured query params (code/state or error). */
  def await: Map[String, String] < (Sync & Async) = promise.get

  def stop: Unit < (Sync & Async) =
    Sync.defer(try socket.close() catch case _: Exception => ())

object CallbackServer:

  private val successHtml =
    "<html><body><h2>Authorization Successful</h2>" +
      "<p>You can close this tab and return to apollo.</p></body></html>"

  private def failureHtml(error: String) =
    s"<html><body><h2>Authorization Failed</h2><p>Error: $error</p></body></html>"

  /** Binds on `127.0.0.1:port` (0 = ephemeral) and serves the one callback
    * on a background fiber.
    */
  def start(port: Int): Result[String, CallbackServer] < (Sync & Async) =
    Promise.init[Map[String, String], Any].map { promise =>
      Abort.run[Throwable](Abort.catching[Throwable](Sync.defer {
        new java.net.ServerSocket(port, 1, java.net.InetAddress.getByName("127.0.0.1"))
      })).map {
        case Result.Success(socket) =>
          val server = new CallbackServer(socket, promise)
          Fiber.initUnscoped(serve(socket, promise)).andThen(Result.succeed(server))
        case Result.Failure(e) => Result.fail(s"OAuth callback port bind failed: ${e.getMessage}")
        case Result.Panic(e)   => Result.fail(s"OAuth callback port bind failed: ${String.valueOf(e.getMessage)}")
      }
    }

  /** Accepts one connection, parses the request-line query, replies 200, and
    * completes the promise. Runs the blocking socket work off the effect path
    * via `Sync.defer`; `stop` closing the socket unblocks `accept`.
    */
  private def serve(
      socket: java.net.ServerSocket, promise: Promise[Map[String, String], Any]
  ): Unit < (Sync & Async) =
    Sync.defer {
      try
        val conn = socket.accept()
        try
          val in = new java.io.BufferedReader(
            new java.io.InputStreamReader(conn.getInputStream, "UTF-8"))
          val requestLine = Option(in.readLine()).getOrElse("")
          // "GET /callback?code=...&state=... HTTP/1.1"
          val target = requestLine.split(" ") match
            case Array(_, t, _*) => t
            case _               => ""
          val params = McpOAuth.parseQuery(target)
          val body   = if params.contains("error") then failureHtml(params("error")) else successHtml
          val out = conn.getOutputStream
          val resp = s"HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n" +
            s"Content-Length: ${body.getBytes("UTF-8").length}\r\nConnection: close\r\n\r\n$body"
          out.write(resp.getBytes("UTF-8"))
          out.flush()
          Some(params)
        finally try conn.close() catch case _: Exception => ()
      catch case _: Exception => None // socket closed by stop, or I/O error
    }.map {
      case Some(params) => promise.completeDiscard(Result.succeed(params))
      case None         => ()
    }
end CallbackServer

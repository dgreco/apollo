// SPDX-FileCopyrightText: 2026 David Greco <greco@acm.org>
//
// SPDX-License-Identifier: Apache-2.0

package apollo.mcp

import apollo.util.Jx
import apollo.util.Jx.*
import kyo.*
import kyo.Structure.Value

/** Server→client requests (sampling/elicitation) over the streamable-HTTP
  * transport: a request arriving on the SSE stream is dispatched to
  * `onServerRequest` and its reply POSTed back. */
class McpHttpServerRequestSuite extends munit.FunSuite:

  private def run[A](v: A < (Sync & Async & Scope)): A =
    import AllowUnsafe.embrace.danger
    KyoApp.Unsafe.runAndBlock(30.seconds)(Scope.run(v)).getOrThrow

  test("a sampling request on the SSE stream is dispatched and answered") {
    val gotRequests = new java.util.concurrent.ConcurrentLinkedQueue[String]() // method|params seen by handler
    val replyPosts  = new java.util.concurrent.ConcurrentLinkedQueue[String]() // JSON-RPC responses POSTed back

    val mcp = HttpRoute.postText("/mcp").handler { req =>
      val msg    = Jx.parse(req.fields.body).getOrElse(Jx.obj())
      val id     = (msg / "id").asLong
      val method = (msg / "method").asStr
      method match
        case Present("initialize") =>
          HttpResponse.ok(s"""{"jsonrpc":"2.0","id":${id.getOrElse(0L)},"result":{"protocolVersion":"2025-06-18","capabilities":{},"serverInfo":{"name":"f","version":"0"}}}""")
            .setHeader("content-type", "application/json").setHeader("mcp-session-id", "s1")
        case Present("notifications/initialized") =>
          HttpResponse.accepted("")
        case Present("trigger") =>
          // SSE: first a server→client sampling request, then the response to
          // our `trigger` call. The client must dispatch the former and return
          // the latter.
          val serverReq = """{"jsonrpc":"2.0","id":999,"method":"sampling/createMessage","params":{"maxTokens":16}}"""
          val ourResp   = s"""{"jsonrpc":"2.0","id":${id.getOrElse(0L)},"result":{"done":true}}"""
          val sse = s"data: $serverReq\n\ndata: $ourResp\n\n"
          HttpResponse.ok(sse).setHeader("content-type", "text/event-stream")
        case Absent =>
          // No method + has id ⇒ a JSON-RPC response = the client's reply to the
          // server request. Record it.
          if id.nonEmpty then replyPosts.add(req.fields.body)
          HttpResponse.accepted("")
        case _ => HttpResponse.accepted("")
    }

    val outcome = run {
      Abort.run[HttpBindException](HttpServer.init(0, "127.0.0.1")(mcp)).map {
        case Result.Success(server) =>
          val base = s"http://127.0.0.1:${server.port}"
          McpHttpClient.connect("http-fake", s"$base/mcp", Nil, 10.seconds, "test").map {
            case Result.Success(client) =>
              client.onServerRequest = (m, p) =>
                gotRequests.add(s"$m|${Jx.render(p)}")
                Result.succeed(McpSampling.result("sampled-text", "mock-model"))
              client.request("trigger", Absent).map { resp =>
                // give the forked reply POST time to land
                def await(ms: Long): Unit < (Sync & Async) =
                  import scala.jdk.CollectionConverters.*
                  if replyPosts.asScala.nonEmpty || ms <= 0 then ()
                  else Async.sleep(100.millis).andThen(await(ms - 100))
                await(10000).andThen(resp)
              }
            case other => throw new AssertionError(other.toString)
          }
        case other => throw new AssertionError(s"bind failed: $other")
      }
    }

    // our original request completed
    assert(outcome.isSuccess, outcome.toString)
    assertEquals((outcome.getOrElse(Jx.obj()) / "done").asBool, Present(true))

    import scala.jdk.CollectionConverters.*
    val reqs = gotRequests.asScala.toList
    assertEquals(reqs.length, 1, reqs.toString)
    assert(reqs.head.startsWith("sampling/createMessage|"), reqs.head)

    val replies = replyPosts.asScala.toList
    assertEquals(replies.length, 1, replies.toString)
    val reply = Jx.parse(replies.head).getOrElse(Jx.obj())
    assertEquals((reply / "id").asLong, Present(999L))
    assertEquals((reply / "result" / "content" / "text").asStr, Present("sampled-text"))
  }
end McpHttpServerRequestSuite

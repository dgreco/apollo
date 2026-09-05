package apollo.provider

import apollo.core.*
import apollo.util.Jx
import apollo.util.Jx.*
import kyo.*
import kyo.Structure.Value

/** Bedrock Converse wire: request/response mapping unit tests plus a
  * full-stack E2E through a mock SigV4-signed `/converse` endpoint.
  */
class BedrockSuite extends munit.FunSuite:

  private def run[A](v: A < (Sync & Async & Scope)): A =
    import AllowUnsafe.embrace.danger
    KyoApp.Unsafe.runAndBlock(30.seconds)(Scope.run(v)).getOrThrow

  private def runtime(headers: Map[String, String], model: String): ResolvedRuntime =
    ResolvedRuntime(
      providerSlug = "bedrock", displayName = "AWS Bedrock", model = model,
      baseUrl = "https://bedrock-runtime.us-east-1.amazonaws.com", apiKey = Absent,
      apiMode = ApiMode.BedrockConverse, headers = headers, profile = Absent,
      reasoning = Absent, maxTokens = Present(1024), contextLength = Absent, streaming = true)

  private def req(rt: ResolvedRuntime, msgs: List[Message], tools: List[ToolSpec] = Nil) =
    TurnRequest(rt, "you are helpful", msgs, tools)

  // --- unit: request build -------------------------------------------------

  test("buildBody maps system, content blocks, tools, and tool results") {
    val rt = runtime(Map.empty, "anthropic.claude-x")
    val tools = List(ToolSpec("get_time", "gets time", """{"type":"object","properties":{}}"""))
    val msgs = List(
      Message.user("hi"),
      Message(Role.Assistant, List(Content.Text("thinking"), Content.ToolUse("t1", "get_time", """{"tz":"utc"}"""))),
      Message.toolResults(List(Content.ToolResult("t1", "12:00", isError = false)))
    )
    val body = BedrockTransport.buildBody(req(rt, msgs, tools))

    // system is a top-level array of {text}.
    assertEquals((body / "system").asArr.get.head.field("text").asStr, Present("you are helpful"))
    // inferenceConfig.maxTokens comes from the runtime.
    assertEquals((body / "inferenceConfig" / "maxTokens").asLong, Present(1024L))
    // toolConfig.tools[].toolSpec.inputSchema.json
    val tspec = (body / "toolConfig" / "tools").asArr.get.head / "toolSpec"
    assertEquals((tspec / "name").asStr, Present("get_time"))
    assert((tspec / "inputSchema" / "json").nonEmpty)

    val messages = (body / "messages").asArr.get
    assertEquals(messages.length, 3)
    // user text block
    assertEquals((messages(0) / "role").asStr, Present("user"))
    assertEquals((messages(0) / "content").asArr.get.head.field("text").asStr, Present("hi"))
    // assistant toolUse block
    val aBlocks = (messages(1) / "content").asArr.get
    val toolUse = aBlocks.flatMap(_.field("toolUse")).head
    assertEquals((toolUse / "toolUseId").asStr, Present("t1"))
    assertEquals((toolUse / "name").asStr, Present("get_time"))
    assertEquals((toolUse / "input" / "tz").asStr, Present("utc"))
    // tool result rides in a user message as a toolResult block
    assertEquals((messages(2) / "role").asStr, Present("user"))
    val tr = (messages(2) / "content").asArr.get.head / "toolResult"
    assertEquals((tr / "toolUseId").asStr, Present("t1"))
    assertEquals((tr / "status").asStr, Present("success"))
    assertEquals((tr / "content").asArr.get.head.field("text").asStr, Present("12:00"))
  }

  test("buildBody merges consecutive same-role messages (Converse alternation)") {
    val rt = runtime(Map.empty, "m")
    val msgs = List(Message.user("a"), Message.user("b"))
    val messages = (BedrockTransport.buildBody(req(rt, msgs)) / "messages").asArr.get
    assertEquals(messages.length, 1)
    assertEquals((messages(0) / "content").asArr.get.length, 2)
  }

  test("encodePathSegment percent-escapes the modelId colon") {
    assertEquals(
      BedrockTransport.encodePathSegment("anthropic.claude-3-5-sonnet-20241022-v2:0"),
      "anthropic.claude-3-5-sonnet-20241022-v2%3A0")
  }

  // --- unit: response parse ------------------------------------------------

  test("parseComplete reads text, toolUse, usage, and stop reason") {
    val json = Jx.obj(
      "output" -> Jx.obj("message" -> Jx.obj("role" -> Jx.str("assistant"), "content" -> Jx.arr(
        Jx.obj("text" -> Jx.str("here")),
        Jx.obj("toolUse" -> Jx.obj("toolUseId" -> Jx.str("u1"), "name" -> Jx.str("f"),
          "input" -> Jx.obj("x" -> Jx.num(1L))))
      ))),
      "stopReason" -> Jx.str("tool_use"),
      "usage" -> Jx.obj("inputTokens" -> Jx.num(10L), "outputTokens" -> Jx.num(5L))
    )
    BedrockTransport.parseComplete(json) match
      case Result.Success(resp) =>
        assertEquals(resp.stopReason, StopReason.ToolUse)
        assertEquals(resp.usage.inputTokens, 10L)
        assertEquals(resp.usage.outputTokens, 5L)
        val text = resp.message.content.collect { case Content.Text(t) => t }.mkString
        assertEquals(text, "here")
        val tu = resp.message.content.collect { case t: Content.ToolUse => t }.head
        assertEquals(tu.name, "f")
        assertEquals((Jx.parse(tu.arguments).getOrElse(Jx.obj()) / "x").asLong, Present(1L))
      case other => fail(other.toString)
  }

  test("parseComplete surfaces an error body") {
    assert(BedrockTransport.parseComplete(Jx.obj("message" -> Jx.str("AccessDenied"))).isFailure)
  }

  // --- E2E through a mock signed endpoint -----------------------------------

  test("streamTurn signs the request and round-trips a Converse response") {
    val sawAuth = new java.util.concurrent.atomic.AtomicReference[String]("")
    val sawBody = new java.util.concurrent.atomic.AtomicReference[String]("")
    val route = HttpRoute.postText("model" / kyo.Capture[String]("modelId") / "converse").handler { r =>
      sawAuth.set(r.headers.get("authorization").getOrElse(""))
      sawBody.set(r.fields.body)
      HttpResponse.ok(Jx.render(Jx.obj(
        "output" -> Jx.obj("message" -> Jx.obj("role" -> Jx.str("assistant"),
          "content" -> Jx.arr(Jx.obj("text" -> Jx.str("pong from bedrock"))))),
        "stopReason" -> Jx.str("end_turn"),
        "usage" -> Jx.obj("inputTokens" -> Jx.num(3L), "outputTokens" -> Jx.num(2L))
      ))).setHeader("content-type", "application/json")
    }
    val resp = run {
      Abort.run[HttpBindException](HttpServer.init(0, "127.0.0.1")(route)).map {
        case Result.Success(server) =>
          val rt = runtime(Map(
            BedrockTransport.hAccessKey  -> "AKIDEXAMPLE",
            BedrockTransport.hSecretKey  -> "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY",
            BedrockTransport.hRegion     -> "us-east-1",
            BedrockTransport.hEndpoint   -> s"http://127.0.0.1:${server.port}"
          ), "anthropic.claude-3-5-sonnet-20241022-v2:0")
          Abort.run[ProviderError](
            BedrockTransport.streamTurn(req(rt, List(Message.user("ping"))))(_ => ())
          )
        case other => throw new AssertionError(s"bind failed: $other")
      }
    }
    resp match
      case Result.Success(tr) =>
        val text = tr.message.content.collect { case Content.Text(t) => t }.mkString
        assertEquals(text, "pong from bedrock")
        assertEquals(tr.stopReason, StopReason.EndTurn)
        assertEquals(tr.usage.inputTokens, 3L)
      case other => fail(s"turn failed: $other")
    assert(sawAuth.get.startsWith("AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/"), sawAuth.get)
    assert(sawAuth.get.contains("SignedHeaders="), sawAuth.get)
    assert(sawAuth.get.contains("Signature="), sawAuth.get)
    assert(sawBody.get.contains("\"messages\""), sawBody.get)
  }
end BedrockSuite

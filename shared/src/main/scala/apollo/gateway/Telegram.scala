package apollo.gateway

import apollo.agent.TurnCallbacks
import apollo.config.ApolloConfig
import apollo.provider.{ProviderError, Transport}
import apollo.util.Jx
import apollo.util.Jx.*
import kyo.*
import kyo.Structure.Value

/** Telegram connector: Bot API long polling (`getUpdates` with a 50s hold),
  * env-based authorization (TELEGRAM_ALLOWED_USERS / _ALLOW_ALL_USERS /
  * GATEWAY_ALLOW_ALL_USERS / GATEWAY_ALLOWED_USERS; default deny), gateway
  * commands (/start /reset /new /status), typing indicator, and 4096-char
  * reply chunking.
  */
object Telegram:

  private val maxMessage = 4096

  /** One piece of queued work: which chat, its session key, the prompt. */
  private final case class Job(chat: Long, key: String, prompt: String)

  /** Number of turns that may run concurrently (across DIFFERENT chats;
    * the hub mutex still serializes per-session).
    */
  private val consumers = 4

  /** How often the "typing…" indicator is refreshed while a turn runs
    * (Telegram's chat action expires after ~5s).
    */
  private val typingRefresh = 4.seconds

  /** Chats with a turn currently in flight — drives the busy acknowledgment
    * for messages that arrive mid-turn.
    */
  private val activeChats: java.util.Set[Long] =
    java.util.concurrent.ConcurrentHashMap.newKeySet[Long]()

  /** The connector's fibers, returned FLAT so the gateway's single top-level
    * `Async.gather` runs them all as peers.
    *
    * Two decouplings, both load-bearing:
    *   1. The poll loop ONLY enqueues work; consumer fibers run the agent
    *      turns. A long or stuck turn can never stall polling — which is
    *      exactly the reported "gateway stopped receiving messages".
    *   2. The poll loop gets its OWN `HttpClient` (dedicated connection
    *      pool). `getUpdates` and every `sendMessage`/`sendChatAction` all
    *      hit `api.telegram.org`; the shared default client serializes
    *      per-host, so a held long-poll would starve every reply to the same
    *      host forever. A separate pool for the long-poll removes that
    *      contention; sends and provider calls stay on the ambient client.
    */
  def services(token: String, config: ApolloConfig, hub: SessionHub): List[Unit < (Sync & Async)] < Sync =
    for
      queue      <- Channel.initUnscoped[Job](capacity = 1024)
      pollClient <- HttpClient.initUnscoped()
    yield
      val start = Console.printLine("telegram: long-polling started")
        .andThen(HttpClient.let(pollClient)(poll(token, config, hub, queue, offset = 0L)))
      start :: List.fill(consumers)(consume(token, config, hub, queue))

  /** Drains the queue forever, running one turn at a time per consumer. */
  private def consume(
      token: String, config: ApolloConfig, hub: SessionHub, queue: Channel[Job]
  ): Unit < (Sync & Async) =
    Abort.run[Closed | Throwable] {
      Abort.catching[Throwable](queue.take)
    }.map {
      case Result.Success(job) =>
        runJob(token, config, hub, job).andThen(consume(token, config, hub, queue))
      case _ =>
        // Channel closed (shutdown) or a defect taking from it: stop cleanly.
        ()
    }

  private def runJob(
      token: String, config: ApolloConfig, hub: SessionHub, job: Job
  ): Unit < (Sync & Async) =
    // The turn always SUCCEEDS at this level (its errors become a sent
    // message), so racing it against the never-completing typing heartbeat
    // lets the turn win and interrupt the heartbeat when it finishes.
    val turn: Unit < (Sync & Async) =
      val work =
        if !config.gatewayStreamingEnabled then
          hub.turn(job.key, "telegram", job.prompt).map(reply => sendChunked(token, job.chat, reply))
        else
          val streamer = new GatewayStreamer(
            editIntervalMs = (config.gatewayStreamingEditInterval * 1000).toLong,
            bufferThreshold = config.gatewayStreamingBufferThreshold,
            maxMessage = maxMessage,
            chunk = t => t.grouped(maxMessage).toList,
            send = text => sendReturningId(token, job.chat, text),
            edit = (id, text) => editMessage(token, job.chat, id, text),
            chunkedSend = text => sendChunked(token, job.chat, text)
          )
          val callbacks = TurnCallbacks(onTextDelta = t => streamer.onDelta(t))
          hub.turn(job.key, "telegram", job.prompt, callbacks).map(reply => streamer.finalize(reply))
      Abort.run[Throwable](Abort.catching[Throwable](work)).map {
        case Result.Success(_) => ()
        case Result.Failure(e) =>
          Console.printLine(s"telegram: turn failed: ${String.valueOf(e.getMessage).take(300)}")
            .andThen(send(token, job.chat, "Something went wrong handling that message; please try again."))
        case Result.Panic(e) =>
          Console.printLine(s"telegram: turn crashed: ${String.valueOf(e.getMessage).take(300)}")
            .andThen(send(token, job.chat, "Something went wrong handling that message; please try again."))
      }

    val body =
      if config.longRunningNotifications then Async.race(List(turn, typingHeartbeat(token, job.chat)))
      else sendChatAction(token, job.chat).andThen(turn)

    Sync.defer(activeChats.add(job.chat))
      .andThen(body)
      .andThen(Sync.defer { activeChats.remove(job.chat); () })

  /** Refreshes the "typing…" indicator forever (raced against the turn, so it
    * is interrupted the moment the turn completes).
    */
  private def typingHeartbeat(token: String, chat: Long): Unit < (Sync & Async) =
    sendChatAction(token, chat)
      .andThen(Async.sleep(typingRefresh))
      .andThen(typingHeartbeat(token, chat))

  /** Bot API base — overridable (TELEGRAM_API_BASE) for tests/self-hosted
    * Bot API servers.
    */
  private def apiBase: String =
    sys.env.getOrElse("TELEGRAM_API_BASE", "https://api.telegram.org").stripSuffix("/")

  private def api(token: String, method: String): String =
    s"$apiBase/bot$token/$method"

  private def poll(
      token: String, config: ApolloConfig, hub: SessionHub, queue: Channel[Job], offset: Long
  ): Unit < (Sync & Async) =
    Abort.run[ProviderError] {
      // The server holds the connection up to 50s; the client budget must
      // outlast it (kyo-http's default 5s total-lifecycle timeout cannot).
      Transport.getJson(api(token, "getUpdates") + s"?timeout=50&offset=$offset", Nil, timeout = 75.seconds)
    }.map {
      case Result.Success(body) =>
        Jx.parse(body) match
          case Result.Success(json) =>
            val updates = (json / "result").asArr.getOrElse(Chunk.empty).toList
            val nextOffset = updates.flatMap(u => (u / "update_id").asLong.toList).maxOption
              .map(_ + 1).getOrElse(offset)
            val trace =
              if config.verbose then Console.printLine(s"telegram: poll cycle ok (offset=$nextOffset, updates=${updates.length})")
              else (): Unit < (Sync & Async)
            trace
              .andThen(Kyo.foreachDiscard(updates)(u => handleUpdate(token, config, hub, queue, u)))
              .andThen(poll(token, config, hub, queue, nextOffset))
          case _ => backoffAndRetry(token, config, hub, queue, offset, "unparseable getUpdates response")
      case Result.Failure(e) => backoffAndRetry(token, config, hub, queue, offset, e.getMessage)
      case Result.Panic(e)   => backoffAndRetry(token, config, hub, queue, offset, String.valueOf(e.getMessage))
    }

  private def backoffAndRetry(
      token: String, config: ApolloConfig, hub: SessionHub, queue: Channel[Job], offset: Long, reason: String
  ): Unit < (Sync & Async) =
    Console.printLine(s"telegram: poll error (${reason.take(200)}); retrying in 5s")
      .andThen(Async.sleep(5.seconds))
      .andThen(poll(token, config, hub, queue, offset))

  private def handleUpdate(
      token: String, config: ApolloConfig, hub: SessionHub, queue: Channel[Job], update: Value
  ): Unit < (Sync & Async) =
    (update / "message") match
      case Absent => ()
      case Present(msg) =>
        val chatId   = (msg / "chat" / "id").asLong
        val chatType = (msg / "chat" / "type").asStr.getOrElse("private")
        val userId   = (msg / "from" / "id").asLong.map(_.toString)
        val text     = (msg / "text").asStr.getOrElse("")
        chatId match
          case Absent => ()
          case Present(chat) =>
            if text.isEmpty then ()
            else if !authorized(config, userId, chatType) then
              userId match
                case Present(uid) =>
                  send(token, chat,
                    s"Not authorized. Add your user id ($uid) to TELEGRAM_ALLOWED_USERS in ~/.apollo/.env, " +
                      "or set TELEGRAM_ALLOW_ALL_USERS=1.")
                case Absent => ()
            else
              val key = hub.sessionKey("telegram", if chatType == "private" then "private" else chatType,
                chat.toString, userId)
              text.trim match
                case "/start" =>
                  send(token, chat, "☀ apollo gateway connected. Send a message to chat; /reset clears context.")
                case "/reset" | "/new" =>
                  hub.resetSession(key).andThen(send(token, chat, "Conversation cleared."))
                case "/status" =>
                  send(token, chat, s"session: $key")
                case prompt =>
                  // If a turn for this chat is still running, acknowledge that
                  // the new message is queued behind it. Then enqueue and
                  // return immediately — polling continues while a consumer
                  // fiber runs the turn. A full queue drops the message with a
                  // visible notice rather than blocking the poll loop.
                  val ack =
                    if config.busyAck && activeChats.contains(chat) then
                      send(token, chat, "⏳ Still working on your previous message — I'll get to this one next.")
                    else (): Unit < (Sync & Async)
                  ack.andThen {
                    Abort.run[Closed](queue.offer(Job(chat, key, prompt))).map {
                      case Result.Success(true)  => ()
                      case Result.Success(false) =>
                        send(token, chat, "I'm overloaded right now — please resend in a moment.")
                      case _ => ()
                    }
                  }

  private def authorized(config: ApolloConfig, userId: Maybe[String], chatType: String): Boolean =
    val env = config.env
    def allowAll = env.getBool("TELEGRAM_ALLOW_ALL_USERS").getOrElse(false)
      || env.getBool("GATEWAY_ALLOW_ALL_USERS").getOrElse(false)
    def allowlist =
      (env.get("TELEGRAM_ALLOWED_USERS").map(_.split(",").toList).getOrElse(Nil)
        ++ env.get("GATEWAY_ALLOWED_USERS").map(_.split(",").toList).getOrElse(Nil))
        .map(_.trim).filter(_.nonEmpty)
    userId match
      case Absent      => false
      case Present(id) => allowAll || allowlist.contains(id) || allowlist.contains("*")

  private def sendChunked(token: String, chat: Long, text: String): Unit < (Sync & Async) =
    val chunks = text.grouped(maxMessage).toList match
      case Nil => List("(empty response)")
      case cs  => cs
    Kyo.foreachDiscard(chunks)(c => send(token, chat, c))

  private def send(token: String, chat: Long, text: String): Unit < (Sync & Async) =
    val body = Jx.render(Jx.obj("chat_id" -> Jx.num(chat), "text" -> Jx.str(text)))
    Abort.run[ProviderError](Transport.postJson(api(token, "sendMessage"), Nil, body, timeout = 30.seconds)).map {
      case Result.Success(_) => ()
      case Result.Failure(e) => Console.printLine(s"telegram: send failed: ${e.getMessage.take(200)}")
      case Result.Panic(e)   => Console.printLine(s"telegram: send failed: ${e.getMessage}")
    }

  /** Sends a message and returns its message_id (for streaming edits). */
  private def sendReturningId(token: String, chat: Long, text: String): Maybe[String] < (Sync & Async) =
    if text.isBlank then Absent
    else
      val body = Jx.render(Jx.obj("chat_id" -> Jx.num(chat), "text" -> Jx.str(text.take(maxMessage))))
      Abort.run[ProviderError](Transport.postJson(api(token, "sendMessage"), Nil, body, timeout = 30.seconds)).map {
        case Result.Success(resp) =>
          Jx.parse(resp) match
            case Result.Success(v) => (v / "result" / "message_id").asLong.map(_.toString)
            case _                 => Absent
        case _ => Absent
      }

  /** Edits a previously-sent message (streaming updates). */
  private def editMessage(token: String, chat: Long, messageId: String, text: String): Unit < (Sync & Async) =
    val body = Jx.render(Jx.obj(
      "chat_id" -> Jx.num(chat),
      "message_id" -> Jx.num(messageId.toLongOption.getOrElse(0L)),
      "text" -> Jx.str(text.take(maxMessage))))
    Abort.run[ProviderError](Transport.postJson(api(token, "editMessageText"), Nil, body, timeout = 30.seconds)).unit

  private def sendChatAction(token: String, chat: Long): Unit < (Sync & Async) =
    val body = Jx.render(Jx.obj("chat_id" -> Jx.num(chat), "action" -> Jx.str("typing")))
    Abort.run[ProviderError](Transport.postJson(api(token, "sendChatAction"), Nil, body, timeout = 15.seconds)).unit
end Telegram

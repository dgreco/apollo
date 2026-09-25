// SPDX-FileCopyrightText: 2026 David Greco <greco@acm.org>
//
// SPDX-License-Identifier: Apache-2.0

package apollo.gateway

import apollo.agent.TurnCallbacks
import apollo.config.ApolloConfig
import apollo.http.{HttpError, Transport}
import apollo.util.Jx
import apollo.util.Jx.*
import kyo.*
import kyo.Structure.Value

/** Discord connector: raw Gateway websocket (hello → identify/resume →
  * heartbeat → dispatch) + REST sends, mirroring the upstream Hermes
  * adapter's text-only behavior: fail-closed env allowlists
  * (`DISCORD_ALLOWED_USERS` / `_ALLOWED_CHANNELS` / `_IGNORED_CHANNELS` /
  * `_FREE_RESPONSE_CHANNELS` / `_ALLOW_ALL_USERS`, plus the `GATEWAY_*`
  * spellings), mention-gated guild replies with mention stripping, `dm` /
  * `group` session keys on the channel id, 2000-char chunking capped at 8
  * messages, reply-reference on the first chunk, 👀→✅/❌ reactions, and a
  * 12s typing refresh. Voice, threads, attachments, and slash-command
  * registration are out of scope (see README).
  */
object Discord:

  val maxMessage            = 2000
  private val splitThreshold    = 1900
  private val maxSplitMessages  = 8
  private val consumers         = 4
  private val typingRefresh     = 12.seconds
  /** GUILDS + GUILD_MESSAGES + DIRECT_MESSAGES + MESSAGE_CONTENT. */
  private val intents           = 1 + 512 + 4096 + 32768

  private final case class Job(channelId: String, key: String, prompt: String, messageId: String)

  private val activeChannels: java.util.Set[String] =
    java.util.concurrent.ConcurrentHashMap.newKeySet[String]()

  /** channel id → name, learned from GUILD_CREATE (raw MESSAGE_CREATE events
    * carry no channel name; the upstream adapter gets it from discord.py's
    * cache). Lets channel gates match names and `#names` like upstream.
    */
  private val channelNames = new java.util.concurrent.ConcurrentHashMap[String, String]()

  /** Dedup of message ids (Discord replays events on RESUME). */
  private val seenMessages: java.util.Set[String] =
    java.util.Collections.newSetFromMap(
      new java.util.LinkedHashMap[String, java.lang.Boolean](2048, 0.75f, false):
        override def removeEldestEntry(e: java.util.Map.Entry[String, java.lang.Boolean]) = size > 2048
    )

  // --- gates (upstream `_GATE_ENV_KEYS` subset; roles/pairing not ported) --

  private final case class Gates(
      allowedUsers: Set[String],
      allowedChannels: Set[String],
      ignoredChannels: Set[String],
      freeChannels: Set[String],
      allowAll: Boolean,
      allowBots: String,
      requireMention: Boolean,
      reactions: Boolean
  )

  private def truthy(v: Maybe[String]): Boolean =
    v.exists(s => Set("true", "1", "yes").contains(s.trim.toLowerCase))

  private def csv(v: Maybe[String]): Set[String] =
    v.map(_.split(",").toList.map(_.trim).filter(_.nonEmpty).toSet).getOrElse(Set.empty)

  /** Upstream `_clean_discord_id`: strips `<@123>` / `<@!123>` wrappers and
    * a `user:` prefix.
    */
  private[gateway] def cleanId(entry: String): String =
    val unwrapped = "^<@!?(\\d+)>$".r.findFirstMatchIn(entry).map(_.group(1)).getOrElse(entry)
    unwrapped.stripPrefix("user:").trim

  private def gates(config: ApolloConfig): Gates =
    val env = config.env
    Gates(
      // GATEWAY_ALLOWED_USERS unioned in, matching this build's Telegram
      // connector (upstream Discord reads it only for button components).
      allowedUsers = (csv(env.get("DISCORD_ALLOWED_USERS")) ++ csv(env.get("GATEWAY_ALLOWED_USERS")))
        .map(cleanId),
      allowedChannels = csv(env.get("DISCORD_ALLOWED_CHANNELS")),
      ignoredChannels = csv(env.get("DISCORD_IGNORED_CHANNELS")),
      freeChannels = csv(env.get("DISCORD_FREE_RESPONSE_CHANNELS")),
      allowAll = truthy(env.get("DISCORD_ALLOW_ALL_USERS")) || truthy(env.get("GATEWAY_ALLOW_ALL_USERS")),
      allowBots = env.get("DISCORD_ALLOW_BOTS").map(_.trim.toLowerCase).getOrElse("none"),
      // Default ON; disabled only by an explicit false word (upstream parse).
      requireMention = !env.get("DISCORD_REQUIRE_MENTION")
        .exists(v => Set("false", "0", "no", "off").contains(v.trim.toLowerCase)),
      reactions = !env.get("DISCORD_REACTIONS")
        .exists(v => Set("false", "0", "no").contains(v.trim.toLowerCase))
    )
  end gates

  /** Upstream channel-key matching: id, bare name, `#name`. */
  private def channelKeys(channelId: String): Set[String] =
    Option(channelNames.get(channelId)) match
      case Some(name) => Set(channelId, name, s"#$name")
      case None       => Set(channelId)

  /** Upstream `_is_allowed_user` minus roles/pairing: FAIL CLOSED. With no
    * user allowlist, allow-all flags or (guild-only) channel-scoped access
    * can admit; with one, only `*` or a listed id passes.
    */
  private[gateway] def authorizedUser(
      allowedUsers: Set[String],
      allowedChannels: Set[String],
      allowAll: Boolean,
      userId: String,
      isDm: Boolean,
      keys: Set[String]
  ): Boolean =
    if allowedUsers.isEmpty then
      allowAll || (!isDm && (allowedChannels.contains("*") || keys.exists(allowedChannels.contains)))
    else allowedUsers.contains("*") || allowedUsers.contains(userId)

  // --- outbound REST -------------------------------------------------------

  private def apiBase(env: String => Maybe[String]): String =
    env("DISCORD_API_BASE").getOrElse("https://discord.com/api/v10").stripSuffix("/")

  private def authHeaders(token: String): List[(String, String)] =
    List("authorization" -> s"Bot $token")

  /** One message send; `replyTo` adds a non-strict message_reference
    * (upstream `fail_if_not_exists=False`) and the upstream default
    * AllowedMentions (users + replied_user only).
    */
  def send(
      token: String,
      channelId: String,
      text: String,
      env: String => Maybe[String],
      replyTo: Maybe[String] = Absent
  ): Unit < (Sync & Async) =
    if text.isBlank then () // upstream refuses empty sends
    else
      val body = Jx.render(Jx.objOf(
        "content" -> Present(Jx.str(text)),
        "allowed_mentions" -> Present(Jx.obj(
          "parse" -> Jx.arr(Jx.str("users")), "replied_user" -> Jx.bool(true)
        )),
        "message_reference" -> replyTo.map(id => Jx.obj(
          "message_id" -> Jx.str(id), "fail_if_not_exists" -> Jx.bool(false)
        ))
      ))
      Abort.run[HttpError](Transport.postJson(
        s"${apiBase(env)}/channels/$channelId/messages", authHeaders(token), body, timeout = 30.seconds
      )).map {
        case Result.Success(_) => ()
        case Result.Failure(e) => Console.printLine(s"discord: send failed: ${e.getMessage.take(200)}")
        case Result.Panic(e)   => Console.printLine(s"discord: send failed: ${String.valueOf(e.getMessage)}")
      }

  /** Splits on the last newline in each window when one exists past the
    * threshold, hard-cutting otherwise; at most 8 messages, with the
    * upstream truncation notice replacing the rest.
    */
  private[gateway] def split(text: String): List[String] =
    def cut(remaining: String, acc: List[String]): List[String] =
      if remaining.length <= maxMessage then (remaining :: acc).reverse
      else
        val window = remaining.take(maxMessage)
        val at     = window.lastIndexOf('\n') match
          case i if i >= splitThreshold / 2 => i
          case _                            => maxMessage
        cut(remaining.drop(at).dropWhile(_ == '\n'), window.take(at) :: acc)
    val chunks = cut(text, Nil).filter(_.nonEmpty)
    if chunks.length <= maxSplitMessages then chunks
    else
      val kept    = chunks.take(maxSplitMessages - 1)
      val dropped = chunks.drop(maxSplitMessages - 1).map(_.length).sum
      kept :+ (chunks(maxSplitMessages - 1).take(maxMessage - 300) +
        s"\n\n⚠️ **Response truncated** — this reply exceeded the delivery limit ($maxSplitMessages messages). " +
        s"$dropped characters were not delivered; the full response is in the session logs.")

  private def sendChunked(
      token: String, channelId: String, text: String, env: String => Maybe[String], replyTo: Maybe[String]
  ): Unit < (Sync & Async) =
    val chunks = split(if text.isBlank then "(empty response)" else text)
    // reply_to_mode "first" (upstream default): only chunk 0 references.
    Kyo.foreachDiscard(chunks.zipWithIndex) { (chunk, i) =>
      send(token, channelId, chunk, env, if i == 0 then replyTo else Absent)
    }

  /** Posts a message and returns its id (for streaming edits). */
  private def sendReturningId(
      token: String, channelId: String, text: String, env: String => Maybe[String], replyTo: Maybe[String]
  ): Maybe[String] < (Sync & Async) =
    if text.isBlank then Absent
    else
      val body = Jx.render(Jx.objOf(
        "content" -> Present(Jx.str(text.take(maxMessage))),
        "allowed_mentions" -> Present(Jx.obj(
          "parse" -> Jx.arr(Jx.str("users")), "replied_user" -> Jx.bool(true))),
        "message_reference" -> replyTo.map(id => Jx.obj(
          "message_id" -> Jx.str(id), "fail_if_not_exists" -> Jx.bool(false)))
      ))
      Abort.run[HttpError](Transport.postJson(
        s"${apiBase(env)}/channels/$channelId/messages", authHeaders(token), body, timeout = 30.seconds
      )).map {
        case Result.Success(resp) =>
          Jx.parse(resp) match
            case Result.Success(v) => (v / "id").asStr
            case _                 => Absent
        case _ => Absent
      }

  /** Edits a previously-sent message (streaming updates). */
  private def editMessage(
      token: String, channelId: String, messageId: String, text: String, env: String => Maybe[String]
  ): Unit < (Sync & Async) =
    val body = Jx.render(Jx.obj("content" -> Jx.str(text.take(maxMessage))))
    Abort.run[HttpError](Transport.patchJson(
      s"${apiBase(env)}/channels/$channelId/messages/$messageId", authHeaders(token), body, timeout = 30.seconds
    )).unit

  private def typing(token: String, channelId: String, env: String => Maybe[String]): Unit < (Sync & Async) =
    Abort.run[HttpError](Transport.postJson(
      s"${apiBase(env)}/channels/$channelId/typing", authHeaders(token), "", timeout = 15.seconds
    )).unit

  private def react(
      token: String, channelId: String, messageId: String, emoji: String, env: String => Maybe[String]
  ): Unit < (Sync & Async) =
    val encoded = java.net.URLEncoder.encode(emoji, "UTF-8")
    val url = s"${apiBase(env)}/channels/$channelId/messages/$messageId/reactions/$encoded/@me"
    Abort.run[HttpError](Transport.putEmpty(url, authHeaders(token))).unit

  private def unreact(
      token: String, channelId: String, messageId: String, emoji: String, env: String => Maybe[String]
  ): Unit < (Sync & Async) =
    val encoded = java.net.URLEncoder.encode(emoji, "UTF-8")
    val url = s"${apiBase(env)}/channels/$channelId/messages/$messageId/reactions/$encoded/@me"
    Abort.run[HttpError](Transport.deleteEmpty(url, authHeaders(token))).unit

  // --- services ------------------------------------------------------------

  /** The connector's fibers (flat, like the Telegram connector): the gateway
    * websocket loop plus consumer fibers draining the job queue, so a long
    * turn never stalls event dispatch.
    */
  def services(token: String, config: ApolloConfig, hub: SessionHub): List[Unit < (Sync & Async)] < Sync =
    Channel.initUnscoped[Job](capacity = 1024).map { queue =>
      val loop = Console.printLine("discord: gateway connecting")
        .andThen(gatewayLoop(token, config, hub, queue))
      loop :: List.fill(consumers)(consume(token, config, hub, queue))
    }

  private def consume(
      token: String, config: ApolloConfig, hub: SessionHub, queue: Channel[Job]
  ): Unit < (Sync & Async) =
    Abort.run[Closed | Throwable](Abort.catching[Throwable](queue.take)).map {
      case Result.Success(job) =>
        runJob(token, config, hub, job).andThen(consume(token, config, hub, queue))
      case _ => ()
    }

  /** Runs the turn and delivers the reply: when `streaming.enabled`, edits a
    * live message as tokens arrive; otherwise sends the full reply chunked.
    */
  private def runTurnDelivering(
      token: String, config: ApolloConfig, hub: SessionHub, job: Job, env: String => Maybe[String]
  ): Unit < (Sync & Async) =
    if !config.gatewayStreamingEnabled then
      hub.turn(job.key, "discord", job.prompt).map(reply =>
        sendChunked(token, job.channelId, reply, env, Present(job.messageId)))
    else
      val streamer = new GatewayStreamer(
        editIntervalMs = (config.gatewayStreamingEditInterval * 1000).toLong,
        bufferThreshold = config.gatewayStreamingBufferThreshold,
        maxMessage = maxMessage,
        chunk = split,
        send = text => sendReturningId(token, job.channelId, text, env, Present(job.messageId)),
        edit = (id, text) => editMessage(token, job.channelId, id, text, env),
        chunkedSend = text => sendChunked(token, job.channelId, text, env, Present(job.messageId))
      )
      val callbacks = TurnCallbacks(onTextDelta = t => streamer.onDelta(t))
      hub.turn(job.key, "discord", job.prompt, callbacks).map(reply => streamer.finalize(reply))

  private def runJob(
      token: String, config: ApolloConfig, hub: SessionHub, job: Job
  ): Unit < (Sync & Async) =
    val env = config.env.get
    val g   = gates(config)
    val ack = if g.reactions then react(token, job.channelId, job.messageId, "👀", env) else ()
    val turn: Boolean < (Sync & Async) =
      Abort.run[Throwable](Abort.catching[Throwable](runTurnDelivering(token, config, hub, job, env))).map {
        case Result.Success(_) => true
        case failure =>
          Console.printLine(s"discord: turn failed: ${failure.toString.take(300)}")
            .andThen(send(token, job.channelId,
              "Something went wrong handling that message; please try again.", env))
            .andThen(false)
      }
    def typingLoop: Unit < (Sync & Async) =
      typing(token, job.channelId, env).andThen(Async.sleep(typingRefresh)).andThen(typingLoop)
    val body: Boolean < (Sync & Async) =
      if config.longRunningNotifications then Async.race(List(turn, typingLoop.andThen(true)))
      else typing(token, job.channelId, env).andThen(turn)
    Sync.defer(activeChannels.add(job.channelId))
      .andThen(ack)
      .andThen(body)
      .map { ok =>
        val done =
          if g.reactions then
            unreact(token, job.channelId, job.messageId, "👀", env)
              .andThen(react(token, job.channelId, job.messageId, if ok then "✅" else "❌", env))
          else (): Unit < (Sync & Async)
        done.andThen(Sync.defer { activeChannels.remove(job.channelId); () })
      }
  end runJob

  // --- gateway websocket ---------------------------------------------------

  /** Resume state captured from READY. */
  private final case class ResumeState(sessionId: String, resumeUrl: String, seq: Long)
  @volatile private var resumeState: Maybe[ResumeState] = Absent
  @volatile private var selfId: String                  = ""

  private def gatewayUrl(env: String => Maybe[String]): String =
    env("DISCORD_GATEWAY_URL").getOrElse("wss://gateway.discord.gg/?v=10&encoding=json")

  /** Tests flip this off so a finished suite doesn't reconnect forever
    * inside the shared sbt JVM.
    */
  @volatile private[gateway] var reconnectEnabled = true

  /** Test hook: clears the process-global connection state (resume session,
    * self id, dedup/channel caches) so suites sharing this object don't leak
    * a prior run's `resume_gateway_url` into the next. Not used in production
    * (one gateway per process).
    */
  private[gateway] def resetState(): Unit =
    resumeState = Absent
    selfId = ""
    seenMessages.clear()
    channelNames.clear()
    activeChannels.clear()

  private def gatewayLoop(
      token: String, config: ApolloConfig, hub: SessionHub, queue: Channel[Job]
  ): Unit < (Sync & Async) =
    val env = config.env.get
    val url = resumeState.map(_.resumeUrl).getOrElse(gatewayUrl(env))
    Abort.run[Throwable] {
      Abort.catching[Throwable] {
        Abort.run[HttpException](HttpClient.webSocket(url) { ws =>
          connection(ws, token, config, hub, queue)
        })
      }
    }.map { outcome =>
      val reason = outcome match
        case Result.Success(Result.Success(_)) => "closed"
        case Result.Success(other)             => other.toString.take(200)
        case other                             => other.toString.take(200)
      if !reconnectEnabled then Console.printLine("discord: gateway stopped")
      else
        Console.printLine(s"discord: gateway disconnected ($reason); reconnecting in 5s")
          .andThen(Async.sleep(5.seconds))
          .andThen(gatewayLoop(token, config, hub, queue))
    }

  /** One websocket session: hello → identify or resume → heartbeat fiber
    * running alongside the dispatch reader. Returns when the connection
    * should be torn down (op 7/9, missed ack, or peer close); the whole
    * session runs inside a `Scope.run` so the heartbeat fiber is interrupted
    * at that point rather than sleeping out its interval on a dead socket.
    */
  private def connection(
      ws: HttpWebSocket, token: String, config: ApolloConfig, hub: SessionHub, queue: Channel[Job]
  ): Unit < (Sync & Async) =
    val seq   = new java.util.concurrent.atomic.AtomicLong(resumeState.map(_.seq).getOrElse(0L))
    val acked = new java.util.concurrent.atomic.AtomicBoolean(true)

    /** False once the socket is closed — the heartbeat fiber uses it to
      * stop instead of beating a dead connection forever.
      */
    def sendJsonOk(v: Value): Boolean < (Sync & Async) =
      Abort.run[Closed](ws.put(HttpWebSocket.Payload.Text(Jx.render(v)))).map(_.isSuccess)

    def sendJson(v: Value): Unit < (Sync & Async) = sendJsonOk(v).unit

    def identifyOrResume: Unit < (Sync & Async) =
      resumeState match
        case Present(rs) =>
          Console.printLine(s"discord: resuming session ${rs.sessionId.take(8)}…").andThen {
            sendJson(Jx.obj("op" -> Jx.num(6L), "d" -> Jx.obj(
              "token" -> Jx.str(token), "session_id" -> Jx.str(rs.sessionId), "seq" -> Jx.num(rs.seq)
            )))
          }
        case Absent =>
          sendJson(Jx.obj("op" -> Jx.num(2L), "d" -> Jx.obj(
            "token"   -> Jx.str(token),
            "intents" -> Jx.num(intents.toLong),
            "properties" -> Jx.obj(
              "os" -> Jx.str("linux"), "browser" -> Jx.str("apollo"), "device" -> Jx.str("apollo")
            )
          )))

    def heartbeats(intervalMs: Long): Unit < (Sync & Async) =
      Async.sleep(intervalMs.millis).andThen {
        if !acked.get() then Console.printLine("discord: heartbeat unacked (zombie); closing")
          .andThen(ws.close())
        else
          acked.set(false)
          sendJsonOk(Jx.obj("op" -> Jx.num(1L), "d" -> Jx.num(seq.get()))).map {
            case true  => heartbeats(intervalMs)
            case false => () // socket gone: stop beating
          }
      }

    def reader: Unit < (Sync & Async & Scope) =
      Abort.run[Closed](ws.take()).map {
        case Result.Success(HttpWebSocket.Payload.Text(text)) =>
          handleFrame(text).map(continue => if continue then reader else ws.close())
        case Result.Success(_) => reader // binary frames: not used with encoding=json
        case _                 => ()     // channel closed
      }

    def handleFrame(text: String): Boolean < (Sync & Async & Scope) =
      Jx.parse(text) match
        case Result.Success(msg) =>
          (msg / "s").asLong match
            case Present(s) =>
              seq.set(s)
              resumeState = resumeState.map(_.copy(seq = s))
            case Absent => ()
          (msg / "op").asLong.getOrElse(-1L) match
            case 10L => // hello
              val interval = (msg / "d" / "heartbeat_interval").asLong.getOrElse(41250L)
              identifyOrResume
                // Scoped, not unscoped: the session's Scope.run interrupts this
                // fiber when the reader returns. An unscoped one outlives the
                // connection — it sleeps out the full heartbeat interval first,
                // which in tests means a fiber still waking up after the suite
                // (and the sbt daemon's classloader) is gone.
                .andThen(Fiber.init(
                  Async.sleep((interval * scala.util.Random.nextDouble()).toLong.millis)
                    .andThen(heartbeats(interval))
                ).unit)
                .andThen(true)
            case 11L => // heartbeat ack
              acked.set(true)
              true
            case 1L => // server asks for an immediate beat
              sendJson(Jx.obj("op" -> Jx.num(1L), "d" -> Jx.num(seq.get()))).andThen(true)
            case 7L => // reconnect (resumable)
              Console.printLine("discord: server requested reconnect").andThen(false)
            case 9L => // invalid session; d says whether it is resumable
              val resumable = (msg / "d").asBool.getOrElse(false)
              if !resumable then resumeState = Absent
              Console.printLine(s"discord: invalid session (resumable=$resumable)").andThen(false)
            case 0L =>
              dispatch((msg / "t").asStr.getOrElse(""), (msg / "d").getOrElse(Jx.obj()),
                token, config, hub, queue).andThen(true)
            case _ => true
        case _ => true
    end handleFrame

    Scope.run(reader)
  end connection

  // --- dispatch ------------------------------------------------------------

  private def dispatch(
      t: String, d: Value, token: String, config: ApolloConfig, hub: SessionHub, queue: Channel[Job]
  ): Unit < (Sync & Async) =
    t match
      case "READY" =>
        selfId = (d / "user" / "id").asStr.getOrElse("")
        val sessionId = (d / "session_id").asStr.getOrElse("")
        val resumeUrl = (d / "resume_gateway_url").asStr.getOrElse(gatewayUrl(config.env.get))
        resumeState = Present(ResumeState(sessionId, s"$resumeUrl/?v=10&encoding=json", 0L))
        Console.printLine(s"discord: ready as ${(d / "user" / "username").asStr.getOrElse("?")}")
      case "RESUMED" =>
        Console.printLine("discord: session resumed")
      case "GUILD_CREATE" =>
        Sync.defer {
          (d / "channels").asArr.getOrElse(Chunk.empty).foreach { ch =>
            for
              id   <- (ch / "id").asStr
              name <- (ch / "name").asStr
            do channelNames.put(id, name)
          }
        }
      case "MESSAGE_CREATE" => handleMessage(d, token, config, hub, queue)
      case _                => ()

  private val mentionPattern = "<@!?(\\d+)>".r

  private def handleMessage(
      d: Value, token: String, config: ApolloConfig, hub: SessionHub, queue: Channel[Job]
  ): Unit < (Sync & Async) =
    val g          = gates(config)
    val env        = config.env.get
    val messageId  = (d / "id").asStr.getOrElse("")
    val channelId  = (d / "channel_id").asStr.getOrElse("")
    val guildId    = (d / "guild_id").asStr
    val isDm       = guildId.isEmpty
    val authorId   = (d / "author" / "id").asStr.getOrElse("")
    val authorBot  = (d / "author" / "bot").asBool.getOrElse(false)
    val msgType    = (d / "type").asLong.getOrElse(0L)
    val rawContent = (d / "content").asStr.getOrElse("")
    val mentioned  = (d / "mentions").asArr.getOrElse(Chunk.empty)
      .exists(m => (m / "id").asStr.contains(selfId))
      || mentionPattern.findAllMatchIn(rawContent).exists(_.group(1) == selfId)
    val keys = channelKeys(channelId)

    // Admission, in the upstream order: dedup → own → type → bots → allowlist.
    val duplicate = seenMessages.synchronized { !seenMessages.add(messageId) }
    if messageId.isEmpty || duplicate then ()
    else if authorId == selfId then ()
    else if msgType != 0L && msgType != 19L then () // default and reply only
    else if authorBot && (g.allowBots == "none" || (g.allowBots == "mentions" && !mentioned)) then ()
    else if !authorBot
      && !authorizedUser(g.allowedUsers, g.allowedChannels, g.allowAll, authorId, isDm, keys)
    then
      if isDm then
        send(token, channelId,
          s"Not authorized. Add your user id ($authorId) to DISCORD_ALLOWED_USERS in ~/.apollo/.env, " +
            "or set DISCORD_ALLOW_ALL_USERS=true.", env)
      else () // guild denials stay silent (upstream logs, never replies)
    else
      // Guild-only channel gates: ignored beats allowed; then mention gating.
      val channelBlocked =
        !isDm && (
          (g.allowedChannels.nonEmpty && !g.allowedChannels.contains("*")
            && !keys.exists(g.allowedChannels.contains))
          || g.ignoredChannels.contains("*") || keys.exists(g.ignoredChannels.contains)
        )
      val free = g.freeChannels.contains("*") || keys.exists(g.freeChannels.contains)
      val mentionBlocked = !isDm && g.requireMention && !mentioned && !free
      if channelBlocked || mentionBlocked then ()
      else
        val stripped = rawContent
          .replace(s"<@$selfId>", "").replace(s"<@!$selfId>", "").trim
        val chatType = if isDm then "dm" else "group"
        val key      = hub.sessionKey("discord", chatType, channelId, Present(authorId))
        if stripped.isEmpty && mentioned then () // mention-only: dropped (upstream)
        else
          val prompt = if stripped.isEmpty then "(The user sent a message with no text content)" else stripped
          prompt match
            case "/reset" | "/new" =>
              hub.resetSession(key).andThen(send(token, channelId, "Conversation cleared.", env))
            case "/status" =>
              send(token, channelId, s"session: $key", env)
            case _ =>
              val ack =
                if config.busyAck && activeChannels.contains(channelId) then
                  send(token, channelId,
                    "⏳ Still working on your previous message — I'll get to this one next.", env)
                else (): Unit < (Sync & Async)
              ack.andThen {
                Abort.run[Closed](queue.offer(Job(channelId, key, prompt, messageId))).map {
                  case Result.Success(true)  => ()
                  case Result.Success(false) =>
                    send(token, channelId, "I'm overloaded right now — please resend in a moment.", env)
                  case _ => ()
                }
              }
  end handleMessage
end Discord
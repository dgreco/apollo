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

/** Slack connector over **Socket Mode**: `apps.connections.open` (app-level
  * token) yields a `wss://` URL; each `events_api` envelope is ACKed with its
  * `envelope_id` and its `payload.event` dispatched; the Web API
  * (`chat.postMessage`, `auth.test`) is plain REST. Mirrors the upstream
  * text-path behavior: deny-by-default allowlists (`SLACK_ALLOWED_USERS` /
  * `_ALLOW_ALL_USERS`, plus the `GATEWAY_*` spellings), DMs always answered,
  * channels **mention-gated** (`<@bot>` in text) with the bot's mention
  * stripped, `dm`/`group` session keys on the channel id, `/reset` `/new`
  * `/status`, 39000-char chunking, and optional gateway streaming (chat
  * .update edits). Block Kit, threads-as-sessions, attachments, slash-command
  * registration, and reactions are out of scope.
  */
object Slack:

  val maxMessage    = 39000
  private val consumers = 4
  private val typingRefresh = 4.seconds

  private final case class Job(channel: String, key: String, prompt: String)

  @volatile private var selfId: String = ""
  private val seenTs: java.util.Set[String] =
    java.util.Collections.newSetFromMap(
      new java.util.LinkedHashMap[String, java.lang.Boolean](2048, 0.75f, false):
        override def removeEldestEntry(e: java.util.Map.Entry[String, java.lang.Boolean]) = size > 2048)
  private val activeChannels: java.util.Set[String] =
    java.util.concurrent.ConcurrentHashMap.newKeySet[String]()

  /** Tests disable reconnect so a finished suite doesn't loop in the shared JVM. */
  @volatile private[gateway] var reconnectEnabled = true
  private[gateway] def resetState(): Unit =
    selfId = ""; seenTs.clear(); activeChannels.clear()

  // --- gates (upstream subset) --------------------------------------------

  private[gateway] final case class Gates(
      allowedUsers: Set[String], allowAll: Boolean, allowBots: String, requireMention: Boolean)

  private def truthy(v: Maybe[String]): Boolean =
    v.exists(s => Set("true", "1", "yes").contains(s.trim.toLowerCase))
  private def csv(v: Maybe[String]): Set[String] =
    v.map(_.split(",").toList.map(_.trim).filter(_.nonEmpty).toSet).getOrElse(Set.empty)

  private def gates(config: ApolloConfig): Gates =
    val env = config.env
    Gates(
      allowedUsers = csv(env.get("SLACK_ALLOWED_USERS")) ++ csv(env.get("GATEWAY_ALLOWED_USERS")),
      allowAll = truthy(env.get("SLACK_ALLOW_ALL_USERS")) || truthy(env.get("GATEWAY_ALLOW_ALL_USERS")),
      allowBots = env.get("SLACK_ALLOW_BOTS").map(_.trim.toLowerCase).getOrElse("none"),
      requireMention = !env.get("SLACK_REQUIRE_MENTION")
        .exists(v => Set("false", "0", "no", "off").contains(v.trim.toLowerCase)))

  /** Deny-by-default: allow-all flag, else membership (`*` = any). */
  private[gateway] def authorizedUser(g: Gates, userId: String): Boolean =
    if g.allowAll then true
    else if g.allowedUsers.isEmpty then false
    else g.allowedUsers.contains("*") || g.allowedUsers.contains(userId)

  // --- REST ----------------------------------------------------------------

  private def apiBase(env: String => Maybe[String]): String =
    env("SLACK_API_BASE").getOrElse("https://slack.com/api").stripSuffix("/")

  private def post(
      token: String, method: String, body: Value, env: String => Maybe[String]
  ): Maybe[Value] < (Sync & Async) =
    Abort.run[HttpError](Transport.postJson(
      s"${apiBase(env)}/$method", List("authorization" -> s"Bearer $token"),
      Jx.render(body), timeout = 30.seconds)).map {
      case Result.Success(resp) =>
        Jx.parse(resp) match
          case Result.Success(v) => Present(v)
          case _                 => Absent
      case _ => Absent
    }

  /** Lightweight Markdown → Slack mrkdwn: `**b**`→`*b*`, `[t](u)`→`<u|t>`,
    * and defuse `<!channel>`/`<!here>`/`<!everyone>` broadcast pings.
    */
  private[gateway] def toMrkdwn(text: String): String =
    "\\[([^\\]]+)\\]\\((https?://[^)\\s]+)\\)".r.replaceAllIn(
      "\\*\\*([^*]+)\\*\\*".r.replaceAllIn(text, m => "*" + java.util.regex.Matcher.quoteReplacement(m.group(1)) + "*"),
      m => "<" + m.group(2) + "|" + java.util.regex.Matcher.quoteReplacement(m.group(1)) + ">")
      .replace("<!channel>", "<!channel|channel>").replace("<!here>", "<!here|here>")
      .replace("<!everyone>", "<!everyone|everyone>")

  def sendChunked(token: String, channel: String, text: String, env: String => Maybe[String]): Unit < (Sync & Async) =
    val chunks = toMrkdwn(if text.isBlank then "(empty response)" else text).grouped(maxMessage).toList
    Kyo.foreachDiscard(chunks)(c => post(token, "chat.postMessage",
      Jx.obj("channel" -> Jx.str(channel), "text" -> Jx.str(c), "mrkdwn" -> Jx.bool(true)), env).unit)

  private def sendReturningTs(token: String, channel: String, text: String, env: String => Maybe[String]): Maybe[String] < (Sync & Async) =
    if text.isBlank then Absent
    else post(token, "chat.postMessage",
      Jx.obj("channel" -> Jx.str(channel), "text" -> Jx.str(toMrkdwn(text).take(maxMessage)),
        "mrkdwn" -> Jx.bool(true)), env).map(_.flatMap(v => (v / "ts").asStr))

  private def editMessage(token: String, channel: String, ts: String, text: String, env: String => Maybe[String]): Unit < (Sync & Async) =
    post(token, "chat.update",
      Jx.obj("channel" -> Jx.str(channel), "ts" -> Jx.str(ts),
        "text" -> Jx.str(toMrkdwn(text).take(maxMessage)), "mrkdwn" -> Jx.bool(true)), env).unit

  // --- services / consumers ------------------------------------------------

  def services(
      botToken: String, appToken: String, config: ApolloConfig, hub: SessionHub
  ): List[Unit < (Sync & Async)] < Sync =
    Channel.initUnscoped[Job](capacity = 1024).map { queue =>
      val loop = Console.printLine("slack: socket mode connecting")
        .andThen(gatewayLoop(botToken, appToken, config, hub, queue))
      loop :: List.fill(consumers)(consume(botToken, config, hub, queue))
    }

  private def consume(token: String, config: ApolloConfig, hub: SessionHub, queue: Channel[Job]): Unit < (Sync & Async) =
    Abort.run[Closed | Throwable](Abort.catching[Throwable](queue.take)).map {
      case Result.Success(job) =>
        runJob(token, config, hub, job).andThen(consume(token, config, hub, queue))
      case _ => ()
    }

  private def runJob(token: String, config: ApolloConfig, hub: SessionHub, job: Job): Unit < (Sync & Async) =
    val env = config.env.get
    val work: Unit < (Sync & Async) =
      if !config.gatewayStreamingEnabled then
        hub.turn(job.key, "slack", job.prompt).map(reply => sendChunked(token, job.channel, reply, env))
      else
        val streamer = new GatewayStreamer(
          editIntervalMs = (config.gatewayStreamingEditInterval * 1000).toLong,
          bufferThreshold = config.gatewayStreamingBufferThreshold, maxMessage = maxMessage,
          chunk = t => toMrkdwn(t).grouped(maxMessage).toList,
          send = text => sendReturningTs(token, job.channel, text, env),
          edit = (ts, text) => editMessage(token, job.channel, ts, text, env),
          chunkedSend = text => sendChunked(token, job.channel, text, env))
        hub.turn(job.key, "slack", job.prompt, TurnCallbacks(onTextDelta = t => streamer.onDelta(t)))
          .map(reply => streamer.finalize(reply))
    def typingLoop: Unit < (Sync & Async) =
      typing(token, job.channel, env).andThen(Async.sleep(typingRefresh)).andThen(typingLoop)
    val guarded: Unit < (Sync & Async) =
      Abort.run[Throwable](Abort.catching[Throwable](work)).map {
        case Result.Success(_) => ()
        case failure =>
          Console.printLine(s"slack: turn failed: ${failure.toString.take(200)}")
            .andThen(sendChunked(token, job.channel,
              "Something went wrong handling that message; please try again.", env))
      }
    val body =
      if config.longRunningNotifications then Async.race(List(guarded, typingLoop))
      else guarded
    Sync.defer(activeChannels.add(job.channel))
      .andThen(body)
      .andThen(Sync.defer { activeChannels.remove(job.channel); () })

  private def typing(token: String, channel: String, env: String => Maybe[String]): Unit < (Sync & Async) =
    // Slack's only "typing" is an AI-assistant thread status; safe to no-op.
    Sync.defer(())

  // --- socket mode ---------------------------------------------------------

  private def gatewayLoop(
      botToken: String, appToken: String, config: ApolloConfig, hub: SessionHub, queue: Channel[Job]
  ): Unit < (Sync & Async) =
    val env = config.env.get
    ensureIdentity(botToken, env).andThen {
      openSocketUrl(appToken, env).map {
        case Absent =>
          reconnectAfter("could not open a Socket Mode connection", botToken, appToken, config, hub, queue)
        case Present(url) =>
          Abort.run[Throwable](Abort.catching[Throwable](
            Abort.run[HttpException](HttpClient.webSocket(url) { ws =>
              connection(ws, botToken, config, hub, queue)
            }))).map { _ =>
            reconnectAfter("socket closed", botToken, appToken, config, hub, queue)
          }
      }
    }

  private def reconnectAfter(
      reason: String, botToken: String, appToken: String,
      config: ApolloConfig, hub: SessionHub, queue: Channel[Job]
  ): Unit < (Sync & Async) =
    if !reconnectEnabled then Console.printLine("slack: stopped")
    else Console.printLine(s"slack: $reason; reconnecting in 5s")
      .andThen(Async.sleep(5.seconds))
      .andThen(gatewayLoop(botToken, appToken, config, hub, queue))

  /** `auth.test` → the bot's own user id (for self-mention/echo suppression). */
  private def ensureIdentity(botToken: String, env: String => Maybe[String]): Unit < (Sync & Async) =
    if selfId.nonEmpty then ()
    else post(botToken, "auth.test", Jx.obj(), env).map {
      case Present(v) => (v / "user_id").asStr.foreach(id => selfId = id)
      case Absent     => ()
    }

  /** `apps.connections.open` (app-level token) → the `wss://` URL, or a test
    * override (`SLACK_SOCKET_URL`).
    */
  private def openSocketUrl(appToken: String, env: String => Maybe[String]): Maybe[String] < (Sync & Async) =
    env("SLACK_SOCKET_URL") match
      case Present(u) => Present(u)
      case Absent =>
        post(appToken, "apps.connections.open", Jx.obj(), env).map {
          case Present(v) if (v / "ok").asBool.getOrElse(false) => (v / "url").asStr
          case _                                                => Absent
        }

  private def connection(
      ws: HttpWebSocket, botToken: String, config: ApolloConfig, hub: SessionHub, queue: Channel[Job]
  ): Unit < (Sync & Async) =
    def reader: Unit < (Sync & Async) =
      Abort.run[Closed](ws.take()).map {
        case Result.Success(HttpWebSocket.Payload.Text(text)) =>
          handleFrame(ws, text, botToken, config, hub, queue).map(cont => if cont then reader else ())
        case Result.Success(_) => reader
        case _                 => ()
      }
    reader

  private def handleFrame(
      ws: HttpWebSocket, text: String, botToken: String,
      config: ApolloConfig, hub: SessionHub, queue: Channel[Job]
  ): Boolean < (Sync & Async) =
    Jx.parse(text) match
      case Result.Success(msg) =>
        (msg / "type").asStr.getOrElse("") match
          case "hello"      => true
          case "disconnect" => false // reconnect via a fresh apps.connections.open
          case "events_api" =>
            // ACK first (Slack auto-disables on unacked envelopes), then dispatch.
            val ack: Unit < (Sync & Async) = (msg / "envelope_id").asStr match
              case Present(id) => Abort.run[Closed](ws.put(
                  HttpWebSocket.Payload.Text(Jx.render(Jx.obj("envelope_id" -> Jx.str(id)))))).unit
              case Absent => Sync.defer(())
            ack.andThen {
              (msg / "payload" / "event") match
                case Present(event) => dispatch(event, botToken, config, hub, queue).andThen(true)
                case Absent         => true
            }
          case _ => true // slash_commands/interactive/etc.: no-op (would still be ACKed above if enveloped)
      case _ => true

  private def dispatch(
      event: Value, botToken: String, config: ApolloConfig, hub: SessionHub, queue: Channel[Job]
  ): Unit < (Sync & Async) =
    (event / "type").asStr.getOrElse("") match
      case "message" => handleMessage(event, botToken, config, hub, queue)
      case _         => ()

  private val mentionRe = "<@([A-Z0-9]+)>".r

  private def handleMessage(
      event: Value, botToken: String, config: ApolloConfig, hub: SessionHub, queue: Channel[Job]
  ): Unit < (Sync & Async) =
    val env       = config.env.get
    val g         = gates(config)
    val ts        = (event / "ts").asStr.getOrElse("")
    val channel   = (event / "channel").asStr.getOrElse("")
    val user      = (event / "author" / "id").asStr.orElse((event / "user").asStr).getOrElse("")
    val subtype   = (event / "subtype").asStr
    val botId     = (event / "bot_id").asStr
    val chanType  = (event / "channel_type").asStr.getOrElse("channel")
    val rawText   = (event / "text").asStr.getOrElse("")
    val isDm      = chanType == "im"
    val mentioned = mentionRe.findAllMatchIn(rawText).exists(_.group(1) == selfId)

    val duplicate = ts.isEmpty || seenTs.synchronized(!seenTs.add(ts))
    if duplicate then ()
    else if user == selfId then ()          // own message
    else if subtype.nonEmpty then ()        // edits/joins/etc. — plain messages only
    else if botId.nonEmpty && (g.allowBots == "none" || (g.allowBots == "mentions" && !mentioned)) then ()
    else if user.isEmpty || channel.isEmpty then ()
    else if !authorizedUser(g, user) then
      if isDm then sendChunked(botToken, channel,
        s"Not authorized. Add your Slack user id ($user) to SLACK_ALLOWED_USERS, or set SLACK_ALLOW_ALL_USERS=true.", env)
      else () // channel denials stay silent
    else if !isDm && g.requireMention && !mentioned then ()
    else
      val stripped = selfId match
        case "" => rawText.trim
        case id => rawText.replace(s"<@$id>", "").trim
      val chatType = if isDm then "dm" else "group"
      val key      = hub.sessionKey("slack", chatType, channel, Present(user))
      if stripped.isEmpty && mentioned then ()
      else
        val prompt = if stripped.isEmpty then "(The user sent a message with no text content)" else stripped
        prompt match
          case "/reset" | "/new" =>
            hub.resetSession(key).andThen(sendChunked(botToken, channel, "Conversation cleared.", env))
          case "/status" =>
            sendChunked(botToken, channel, s"session: $key", env)
          case _ =>
            def enqueue: Unit < (Sync & Async) =
              Abort.run[Closed](queue.offer(Job(channel, key, prompt))).map {
                case Result.Success(true)  => ()
                case Result.Success(false) => sendChunked(botToken, channel, "I'm overloaded right now — please resend in a moment.", env)
                case _ => ()
              }
            if config.busyAck && activeChannels.contains(channel) then
              sendChunked(botToken, channel,
                "⏳ Still working on your previous message — I'll get to this one next.", env).andThen(enqueue)
            else enqueue
  end handleMessage
end Slack

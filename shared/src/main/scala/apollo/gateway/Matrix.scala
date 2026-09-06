package apollo.gateway

import apollo.config.ApolloConfig
import apollo.provider.{ProviderError, Transport}
import apollo.util.Jx
import apollo.util.Jx.*
import kyo.*
import kyo.Structure.Value

/** Matrix connector: the client-server API over HTTP — `/sync` long-poll for
  * inbound messages, PUT `.../send/m.room.message` for replies. Auth is a static
  * access token (MATRIX_ACCESS_TOKEN) against a homeserver (MATRIX_HOMESERVER);
  * no OAuth. Mirrors Telegram's decoupled poll→queue→consumer structure so a
  * stuck turn never stalls polling. Non-streaming replies. */
object Matrix:

  private val maxMessage = 30000
  private val consumers  = 4
  private val txnBase    = java.lang.System.nanoTime()
  private val txnSeq     = new java.util.concurrent.atomic.AtomicLong(0L)

  private final case class Job(room: String, key: String, prompt: String)
  final case class RoomMsg(room: String, sender: String, body: String)

  /** homeserver base, access token → the connector's flat fiber list. */
  def services(homeserver: String, token: String, config: ApolloConfig, hub: SessionHub): List[Unit < (Sync & Async)] < Sync =
    for
      queue      <- Channel.initUnscoped[Job](capacity = 1024)
      pollClient <- HttpClient.initUnscoped()
    yield
      val start = Console.printLine(s"matrix: syncing $homeserver")
        .andThen(HttpClient.let(pollClient)(bootstrap(homeserver, token, config, hub, queue)))
      start :: List.fill(consumers)(consume(homeserver, token, config, hub, queue))

  private def base(homeserver: String): String = homeserver.stripSuffix("/") + "/_matrix/client/v3"
  private def auth(token: String)              = List("authorization" -> s"Bearer $token")

  /** whoami (for self-filtering) then an initial batch pointer, then poll. */
  private def bootstrap(
      homeserver: String, token: String, config: ApolloConfig, hub: SessionHub, queue: Channel[Job]
  ): Unit < (Sync & Async) =
    whoami(homeserver, token).map { self =>
      // Initial sync (no `since`): take next_batch, ignore the backlog.
      Abort.run[ProviderError](Transport.getJson(s"${base(homeserver)}/sync?timeout=0", auth(token), timeout = 30.seconds)).map {
        case Result.Success(body) =>
          val (nb, _) = parseSync(Jx.parse(body).getOrElse(Jx.obj()))
          poll(homeserver, token, config, hub, queue, self, nb)
        case _ => poll(homeserver, token, config, hub, queue, self, Absent)
      }
    }

  private def whoami(homeserver: String, token: String): Maybe[String] < (Sync & Async) =
    Abort.run[ProviderError](Transport.getJson(s"${base(homeserver)}/account/whoami", auth(token), timeout = 15.seconds)).map {
      case Result.Success(body) => (Jx.parse(body).getOrElse(Jx.obj()) / "user_id").asStr
      case _                    => sys.env.get("MATRIX_USER_ID") match { case Some(u) => Present(u); case None => Absent }
    }

  /** Pure: next_batch + the m.text messages in this sync response. */
  def parseSync(json: Value): (Maybe[String], List[RoomMsg]) =
    val nb = (json / "next_batch").asStr
    val rooms = (json / "rooms" / "join").asObj.getOrElse(Chunk.empty)
    val msgs = rooms.toList.flatMap { (roomId, room) =>
      (room / "timeline" / "events").asArr.getOrElse(Chunk.empty).toList.flatMap { e =>
        if (e / "type").asStr == Present("m.room.message") && (e / "content" / "msgtype").asStr == Present("m.text") then
          (for
            s <- (e / "sender").asStr
            b <- (e / "content" / "body").asStr
          yield RoomMsg(roomId, s, b)).toList
        else Nil
      }
    }
    (nb, msgs)

  private def poll(
      homeserver: String, token: String, config: ApolloConfig, hub: SessionHub,
      queue: Channel[Job], self: Maybe[String], since: Maybe[String]
  ): Unit < (Sync & Async) =
    val sinceParam = since.map(s => "&since=" + java.net.URLEncoder.encode(s, "UTF-8")).getOrElse("")
    Abort.run[ProviderError] {
      Transport.getJson(s"${base(homeserver)}/sync?timeout=30000$sinceParam", auth(token), timeout = 55.seconds)
    }.map {
      case Result.Success(body) =>
        val (nb, msgs) = parseSync(Jx.parse(body).getOrElse(Jx.obj()))
        val next = nb.orElse(since)
        Kyo.foreachDiscard(msgs)(m => handle(homeserver, token, config, hub, queue, self, m))
          .andThen(poll(homeserver, token, config, hub, queue, self, next))
      case Result.Failure(e) => retry(homeserver, token, config, hub, queue, self, since, e.getMessage)
      case Result.Panic(e)   => retry(homeserver, token, config, hub, queue, self, since, String.valueOf(e.getMessage))
    }

  private def retry(
      homeserver: String, token: String, config: ApolloConfig, hub: SessionHub,
      queue: Channel[Job], self: Maybe[String], since: Maybe[String], reason: String
  ): Unit < (Sync & Async) =
    Console.printLine(s"matrix: sync error (${reason.take(200)}); retry in 5s")
      .andThen(Async.sleep(5.seconds))
      .andThen(poll(homeserver, token, config, hub, queue, self, since))

  private def handle(
      homeserver: String, token: String, config: ApolloConfig, hub: SessionHub,
      queue: Channel[Job], self: Maybe[String], m: RoomMsg
  ): Unit < (Sync & Async) =
    if self.contains(m.sender) || m.body.isEmpty then ()          // ignore our own / empty
    else if !authorized(config, m.sender) then
      send(homeserver, token, m.room,
        s"Not authorized. Add ${m.sender} to MATRIX_ALLOWED_USERS, or set MATRIX_ALLOW_ALL_USERS=1.")
    else
      val key = hub.sessionKey("matrix", "room", m.room, Present(m.sender))
      m.body.trim match
        case "/reset" | "/new" => hub.resetSession(key).andThen(send(homeserver, token, m.room, "Conversation cleared."))
        case "/status"         => send(homeserver, token, m.room, s"session: $key")
        case prompt =>
          Abort.run[Closed](queue.offer(Job(m.room, key, prompt))).map {
            case Result.Success(false) => send(homeserver, token, m.room, "I'm overloaded right now — please resend in a moment.")
            case _                     => ()
          }

  private def consume(
      homeserver: String, token: String, config: ApolloConfig, hub: SessionHub, queue: Channel[Job]
  ): Unit < (Sync & Async) =
    Abort.run[Closed | Throwable](Abort.catching[Throwable](queue.take)).map {
      case Result.Success(job) => runJob(homeserver, token, config, hub, job).andThen(consume(homeserver, token, config, hub, queue))
      case _                   => ()
    }

  private def runJob(
      homeserver: String, token: String, config: ApolloConfig, hub: SessionHub, job: Job
  ): Unit < (Sync & Async) =
    Abort.run[Throwable](Abort.catching[Throwable](
      hub.turn(job.key, "matrix", job.prompt).map(reply => sendChunked(homeserver, token, job.room, reply))
    )).map {
      case Result.Success(_) => ()
      case Result.Failure(e) =>
        Console.printLine(s"matrix: turn failed: ${String.valueOf(e.getMessage).take(300)}")
          .andThen(send(homeserver, token, job.room, "Something went wrong handling that message; please try again."))
      case Result.Panic(e) =>
        Console.printLine(s"matrix: turn crashed: ${String.valueOf(e.getMessage).take(300)}")
          .andThen(send(homeserver, token, job.room, "Something went wrong handling that message; please try again."))
    }

  private def authorized(config: ApolloConfig, sender: String): Boolean =
    val env = config.env
    val allowAll = env.getBool("MATRIX_ALLOW_ALL_USERS").getOrElse(false) || env.getBool("GATEWAY_ALLOW_ALL_USERS").getOrElse(false)
    val allowlist =
      (env.get("MATRIX_ALLOWED_USERS").map(_.split(",").toList).getOrElse(Nil)
        ++ env.get("GATEWAY_ALLOWED_USERS").map(_.split(",").toList).getOrElse(Nil)).map(_.trim).filter(_.nonEmpty)
    allowAll || allowlist.contains(sender) || allowlist.contains("*")

  private def sendChunked(homeserver: String, token: String, room: String, text: String): Unit < (Sync & Async) =
    val chunks = text.grouped(maxMessage).toList match { case Nil => List("(empty response)"); case cs => cs }
    Kyo.foreachDiscard(chunks)(c => send(homeserver, token, room, c))

  private def send(homeserver: String, token: String, room: String, text: String): Unit < (Sync & Async) =
    val txn = s"apollo-$txnBase-${txnSeq.incrementAndGet()}"
    val url = s"${base(homeserver)}/rooms/${java.net.URLEncoder.encode(room, "UTF-8")}/send/m.room.message/$txn"
    val body = Jx.render(Jx.obj("msgtype" -> Jx.str("m.text"), "body" -> Jx.str(text)))
    Abort.run[ProviderError](Transport.putJson(url, auth(token), body, timeout = 30.seconds)).map {
      case Result.Success(_) => ()
      case Result.Failure(e) => Console.printLine(s"matrix: send failed: ${e.getMessage.take(200)}")
      case Result.Panic(e)   => Console.printLine(s"matrix: send failed: ${String.valueOf(e.getMessage)}")
    }
end Matrix

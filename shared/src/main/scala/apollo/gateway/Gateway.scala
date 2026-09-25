// SPDX-FileCopyrightText: 2026 David Greco <greco@acm.org>
//
// SPDX-License-Identifier: Apache-2.0

package apollo.gateway

import apollo.agent.{Agent, SystemPrompt, TurnCallbacks}
import apollo.config.BuildInfo
import apollo.config.Yaml.*
import apollo.config.{ApolloConfig, ApolloPaths}
import apollo.core.Message
import apollo.provider.*
import apollo.session.{SessionMeta, SessionStore, HandoffStore}
import apollo.skills.SkillStore
import apollo.tools.*
import apollo.util.Style
import kyo.*

/** The messaging gateway: one process hosting the platform connectors this
  * build implements — Telegram (Bot API long polling), the OpenAI-compatible
  * API server, and the generic webhook — with upstream-compatible session keys
  * (`agent:main:<platform>:<chat_type>:<chat_id>[:<user>]`) and env-based
  * authorization (allowlists, allow-all flags, default deny).
  */
object Gateway:

  def command(args: List[String], config: ApolloConfig, paths: ApolloPaths): Unit < (Sync & Async & Scope) =
    args.headOption.getOrElse("run") match
      case "run" | "start" => run(config, paths)
      case "status" =>
        Console.printLine("gateway status: not running (this build runs the gateway in the foreground)")
      case other =>
        Console.printLine(s"gateway subcommands: run | status (got: $other)")

  def run(config: ApolloConfig, paths: ApolloPaths): Unit < (Sync & Async & Scope) =
    val env      = config.env
    val telegram = env.get("TELEGRAM_BOT_TOKEN")
    val discord  = env.get("DISCORD_BOT_TOKEN")
    val slackBot = env.get("SLACK_BOT_TOKEN")
    val slackApp = env.get("SLACK_APP_TOKEN")
    val matrixHs = env.get("MATRIX_HOMESERVER")
    val matrixTok = env.get("MATRIX_ACCESS_TOKEN")
    val whatsappTok = env.get("WHATSAPP_TOKEN")
    val twilioSid   = env.get("TWILIO_ACCOUNT_SID")
    val teamsAppId  = env.get("TEAMS_APP_ID")
    val imessageOn  = env.getBool("IMESSAGE_ENABLED").getOrElse(false)
    val emailHost   = env.get("EMAIL_IMAP_HOST")
    val apiKey   = env.get("API_SERVER_KEY")
      .orElse(config.platformConfig("api_server").flatMap(_.path("extra", "key")).flatMap(_.str))
    val apiEnabled = config.platformEnabled("api_server").getOrElse(apiKey.nonEmpty)
    val webhookSecret = env.get("WEBHOOK_SECRET")
    val webhookEnabled = config.platformEnabled("webhook").getOrElse(false) || webhookSecret.nonEmpty

    Abort.run[ResolveError](Runtime.resolve(config, RuntimeOverrides())).map {
      case Result.Failure(err) => Console.printLine(Style.red(s"gateway: ${err.message}"))
      case Result.Panic(e)     => Console.printLine(Style.red(s"gateway: ${e.getMessage}"))
      case Result.Success(runtime) =>
        // MCP servers start once for the whole gateway; sessions created
        // later by the hub pick their tools up via the dynamic registry.
        val mcpStart = apollo.mcp.McpManager.start(
          config, paths,
          java.nio.file.Paths.get(".").toAbsolutePath.normalize,
          Absent, BuildInfo.version
        )
        val hub = new SessionHub(config, paths, runtime)
        // Telegram contributes a FLAT list of peer fibers (poll + consumers).
        val telegramServices: List[Unit < (Sync & Async)] < Sync =
          telegram match
            case Present(token) => Telegram.services(token, config, hub)
            case Absent         => Nil
        val discordServices: List[Unit < (Sync & Async)] < Sync =
          discord match
            case Present(token) => Discord.services(token, config, hub)
            case Absent         => Nil
        val slackServices: List[Unit < (Sync & Async)] < Sync =
          (slackBot, slackApp) match
            case (Present(bot), Present(app)) => Slack.services(bot, app, config, hub)
            case (Present(_), Absent) =>
              Console.printLine("slack: SLACK_BOT_TOKEN set but SLACK_APP_TOKEN missing (Socket Mode needs both)")
                .andThen(Nil)
            case _ => Nil
        val matrixServices: List[Unit < (Sync & Async)] < Sync =
          (matrixHs, matrixTok) match
            case (Present(hs), Present(tok)) => Matrix.services(hs, tok, config, hub)
            case (Present(_), Absent) =>
              Console.printLine("matrix: MATRIX_HOMESERVER set but MATRIX_ACCESS_TOKEN missing").andThen(Nil)
            case _ => Nil
        val whatsappServices: List[Unit < (Sync & Async)] =
          whatsappTok match
            case Present(_) => WhatsApp.services(config, hub)
            case Absent     => Nil
        val smsServices: List[Unit < (Sync & Async)] =
          twilioSid match
            case Present(_) => Sms.services(config, hub)
            case Absent     => Nil
        val teamsServices: List[Unit < (Sync & Async)] =
          teamsAppId match
            case Present(_) => Teams.services(config, hub)
            case Absent     => Nil
        val imessageServices: List[Unit < (Sync & Async)] =
          if imessageOn then IMessage.services(config, hub) else Nil
        val emailServices: List[Unit < (Sync & Async)] =
          emailHost match
            case Present(_) => Email.services(config, hub)
            case Absent     => Nil
        telegramServices.map { tg =>
          discordServices.map { dc =>
          slackServices.map { sl =>
          matrixServices.map { mx =>
          val platformCount = tg.length + dc.length + sl.length + mx.length +
            whatsappServices.length + smsServices.length + teamsServices.length +
            imessageServices.length + emailServices.length
            + (if apiEnabled then 1 else 0) + (if webhookEnabled then 1 else 0)
          val services =
            tg ++ dc ++ sl ++ mx ++ whatsappServices ++ smsServices ++ teamsServices ++
              imessageServices ++ emailServices
              ++ (if apiEnabled then List(ApiServer.serve(config, hub, apiKey)) else Nil)
              ++ (if webhookEnabled then List(Webhook.serve(config, hub, webhookSecret)) else Nil)
              ++ List(CronScheduler.runLoop(config, paths))
          if platformCount == 0 then
            Console.printLine(
              "gateway: no platforms configured. Set TELEGRAM_BOT_TOKEN, DISCORD_BOT_TOKEN, " +
                "SLACK_BOT_TOKEN+SLACK_APP_TOKEN, MATRIX_HOMESERVER+MATRIX_ACCESS_TOKEN, WHATSAPP_TOKEN, " +
                "TWILIO_ACCOUNT_SID, API_SERVER_KEY, or WEBHOOK_SECRET (env or ~/.apollo/.env)."
            )
          else
            Console.printLine(s"gateway: starting $platformCount platform(s) + cron scheduler")
              .andThen(mcpStart)
              .andThen(Async.gather(services).unit)
          }
          }
          }
        }
    }
end Gateway

/** the upstream harness session-key construction:
  * `agent:main:<platform>:<chat_type>:<chat_id>[:<user>]` — the participant
  * suffix only for group chats when `group_sessions_per_user` is on, byte-
  * compatible with `gateway/session.py::build_session_key` for the platforms
  * this build ships.
  */
object SessionKeys:
  def build(
      platform: String,
      chatType: String,
      chatId: String,
      userId: Maybe[String],
      groupSessionsPerUser: Boolean
  ): String =
    val group = chatType != "private" && chatType != "dm"
    val user =
      if group && groupSessionsPerUser then userId.map(u => s":$u").getOrElse("")
      else ""
    s"agent:main:$platform:$chatType:$chatId$user"

/** Per-conversation agents keyed by upstream-format session keys; turns on the
  * same session are serialized through a mutex.
  */
final class SessionHub(config: ApolloConfig, paths: ApolloPaths, runtime: ResolvedRuntime):

  private final case class Entry(agent: Agent, meter: Meter, toolNames: List[String], sessionId: String)
  private val sessions = new java.util.concurrent.ConcurrentHashMap[String, Entry]()
  private val store    = new SessionStore(paths)

  def sessionKey(platform: String, chatType: String, chatId: String, userId: Maybe[String]): String =
    SessionKeys.build(platform, chatType, chatId, userId, config.groupSessionsPerUser)

  def resetSession(key: String): Unit < Sync =
    Sync.defer {
      sessions.remove(key)
      ()
    }

  /** `callbacks` lets a connector stream text deltas (gateway streaming);
    * default empty for the non-streaming path.
    */
  def turn(
      key: String, platform: String, text: String,
      callbacks: TurnCallbacks = TurnCallbacks()
  ): String < (Sync & Async) =
    entry(key, platform).map { e =>
      runSerialized(e) {
        withDeadline {
          for
            system <- SystemPrompt.build(SystemPrompt.Input(
                        config, paths, new SkillStore(config, paths),
                        java.nio.file.Paths.get(apollo.config.Fs.expand(config.terminalCwd, config.env.get))
                          .toAbsolutePath.normalize,
                        platform, runtime.model, runtime.providerSlug, e.toolNames
                      ))
            result <- e.agent.runTurn(Message.user(text), system, e.toolNames, callbacks)
          yield
            if result.finalResponse.nonEmpty then result.finalResponse
            else s"(turn ended: ${result.exitReason})"
        }
      }
    }

  /** Applies `agent.gateway_timeout` INSIDE the session mutex, so a stuck
    * turn is interrupted and releases the session instead of blocking every
    * later message for it. The wait for the mutex itself is not counted.
    */
  private def withDeadline(v: String < (Sync & Async)): String < (Sync & Async) =
    val seconds = config.gatewayTimeoutSeconds
    if seconds <= 0 then v
    else
      Abort.run[Timeout](Async.timeout(seconds.seconds)(v)).map {
        case Result.Success(s) => s
        case _ => s"(turn timed out after ${seconds}s and was interrupted; send another message to continue)"
      }

  /** Serializes turns per session; a closed meter (impossible here — meters
    * are never closed) degrades to a visible error string.
    */
  private def runSerialized(e: Entry)(v: String < (Sync & Async)): String < (Sync & Async) =
    Abort.run[Closed](e.meter.run(v)).map {
      case Result.Success(s) => s
      case _                 => "(session lock unavailable)"
    }

  private def entry(key: String, platform: String): Entry < (Sync & Async) =
    Option(sessions.get(key)) match
      case Some(e) => e
      case None =>
        for
          now     <- Sync.defer(java.time.Instant.now())
          id       = store.newSessionId(now)
          todoRef <- AtomicRef.init(List.empty[TodoItem])
          meter   <- Meter.initMutexUnscoped
          ctx      = ToolContext(
                       config = config,
                       paths = paths,
                       cwd = java.nio.file.Paths.get(".").toAbsolutePath.normalize,
                       platform = platform,
                       sessionId = id,
                       approvals = new ApprovalService(config, paths, platform, oneShot = false, yoloFlag = false),
                       ui = UnattendedToolUi,
                       todo = todoRef,
                       skills = new SkillStore(config, paths),
                       sessionSearch = Present((q, n) => store.search(q, n))
                     )
          tools    = Toolsets.forPlatform(config, platform)
          flag     = new java.util.concurrent.atomic.AtomicBoolean(false)
          agent    = new Agent(runtime, ctx, store, id, config.maxTurns, flag)
          _       <- store.create(SessionMeta(
                       id = id, title = Absent, platform = platform, model = runtime.model,
                       provider = runtime.providerSlug, startedAt = now.toEpochMilli / 1000.0,
                       endedAt = Absent, cwd = ctx.cwd.toString, messageCount = 0, apiCalls = 0,
                       usage = apollo.core.Usage.zero
                     ))
          // Adopt a pending `/handoff` from the REPL: seed this new session with
          // the handed-off transcript (consume-once).
          handoff <- HandoffStore.consume(paths, platform)
          _       <- handoff match
                       case Present(h) =>
                         store.loadTranscript(h.sessionId).map { msgs =>
                           if msgs.isEmpty then Sync.defer(())
                           else
                             agent.restore(msgs)
                             store.rewriteTranscript(id, msgs)
                         }
                       case Absent => Sync.defer(())
        yield
          val e = Entry(agent, meter, tools, id)
          Option(sessions.putIfAbsent(key, e)).getOrElse(e)
end SessionHub

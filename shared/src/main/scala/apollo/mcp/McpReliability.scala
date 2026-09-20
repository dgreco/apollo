package apollo.mcp

import kyo.*
import kyo.Structure.Value

/** Reliability constants, defaulted to the upstream Hermes values
  * (`mcp_tool.py`): breaker `3 strikes → 60s cooldown → half-open probe`,
  * reconnect budget 5 (3 for initial connects) with capped jittered
  * backoff, parked self-probe every 300s, keepalive RPC timeout 30s, and
  * the ≤15s wait-for-respawn before retrying a call once. Tests inject
  * tiny values.
  */
final case class McpTuning(
    breakerThreshold: Int = 3,
    breakerCooldown: Duration = 60.seconds,
    maxReconnects: Int = 5,
    maxInitialConnects: Int = 3,
    backoffBase: Duration = 2.seconds,
    backoffCap: Duration = 60.seconds,
    parkedProbeInterval: Duration = 300.seconds,
    keepaliveRpcTimeout: Duration = 30.seconds,
    reconnectWait: Duration = 15.seconds
)

private[mcp] enum McpConnectError:
  case McpUnsupported(reason: String)
  case McpConnectFailed(error: String)

/** The per-server supervisor: a `McpConnection` whose `request` wraps the
  * live transport with the upstream reliability ladder — circuit breaker,
  * dead-transport reconnect with retry-once, budget-exhaustion parking with
  * periodic self-probes, and a keepalive loop (ping, falling back to
  * `tools/list` when the server doesn't implement ping, confirming before
  * declaring a timed-out session dead). Tool entries capture the handle, so
  * a reconnected server serves the same registered tools.
  */
final class McpServerHandle(
    val cfg: McpServerConfig,
    tuning: McpTuning,
    openConn: () => Result[McpConnectError, McpConnection] < (Sync & Async),
    listAndRegister: McpConnection => Result[String, Int] < (Sync & Async),
    onState: McpManager.State => Unit
) extends McpConnection:

  def serverName: String = cfg.name

  @volatile private var current: Maybe[McpConnection] = Absent
  @volatile private var closed                        = false
  @volatile private var parkedReason: Maybe[String]   = Absent
  @volatile private var probing                       = false // parked self-probe fiber running
  @volatile private var keepaliveRunning              = false
  @volatile private var pingUnsupported               = false
  @volatile private var breakerOpenedAt: Maybe[Long]  = Absent
  private val strikes        = new java.util.concurrent.atomic.AtomicInteger(0)
  private val reconnectGate  = new java.util.concurrent.atomic.AtomicBoolean(false)

  def alive: Boolean          = current.exists(_.alive)
  def parked: Boolean         = parkedReason.nonEmpty
  def initializeResult: Value = current.map(_.initializeResult).getOrElse(Value.Null)
  def currentStrikes: Int     = strikes.get()

  // --- messages the model sees (upstream literals) -------------------------

  private def transportDownMsg: String =
    s"MCP server '$serverName' transport is down; reconnect requested. " +
      "Do NOT retry this tool immediately — give it a few seconds to come back."

  private def breakerMsg(remainingSeconds: Long): String =
    s"MCP server '$serverName' is unreachable after ${strikes.get()} consecutive failures. " +
      s"Auto-retry available in ~${remainingSeconds}s. Do NOT retry this tool yet — " +
      "use alternative approaches or ask the user to check the MCP server."

  private def parkMsg(reason: String): String =
    s"MCP server '$serverName' is parked after repeated connection failures ($reason). " +
      "It is re-probed periodically; use alternative approaches or ask the user to check the MCP server."

  // --- failure classification ---------------------------------------------

  /** Permanent shapes park immediately (upstream `_classify_mcp_failure`):
    * auth rejections, non-MCP endpoints, invalid URLs, unlaunchable
    * commands.
    */
  private def isPermanent(err: String): Boolean =
    err.contains("401") || err.contains("403") ||
      err.contains("does not speak MCP") || err.contains("invalid MCP url") ||
      err.contains("has no 'command'") || err.contains("failed to launch") ||
      err.contains("apollo mcp login") // OAuth needs interactive (re)auth

  /** A failure worth a transport reconnect: the connection died, or the
    * error is neither a JSON-RPC error from a live server ("' error ") nor
    * a call timeout (which keepalive confirms separately).
    */
  private def isReconnectable(err: String, clientAlive: Boolean): Boolean =
    !clientAlive || (!err.contains("' error ") && !err.contains("timed out"))

  private def isMethodNotFound(err: String): Boolean =
    val low = err.toLowerCase
    err.contains("-32601") || low.contains("method not found") ||
      low.contains("unknown method") || low.contains("not found: ping")

  // --- breaker bookkeeping -------------------------------------------------

  private[mcp] def recordSuccess(): Unit =
    strikes.set(0)
    breakerOpenedAt = Absent

  private[mcp] def recordFailure(): Unit =
    if strikes.incrementAndGet() >= tuning.breakerThreshold then
      breakerOpenedAt = Present(java.lang.System.currentTimeMillis())

  /** An `isError` tool result counts as a breaker strike (upstream). */
  private[mcp] def recordIsErrorStrike(): Unit = recordFailure()

  /** Absent = closed; Present(remaining>0) = open (fail fast); Present(0) =
    * half-open (let one probe call through).
    */
  private def breakerState: Maybe[Long] =
    breakerOpenedAt.map { openedAt =>
      val elapsed = java.lang.System.currentTimeMillis() - openedAt
      ((tuning.breakerCooldown.toMillis - elapsed).max(0L) + 999) / 1000
    }

  // --- lifecycle -----------------------------------------------------------

  /** Initial connect with the upstream initial budget, then keepalive. */
  def start(): Unit < (Sync & Async) =
    Sync.defer(onState(McpManager.State.Connecting)).andThen {
      attemptConnect(tuning.maxInitialConnects).map { ok =>
        if ok then spawnKeepalive() else ()
      }
    }

  /** Bounded connect attempts with capped, jittered backoff; parks on
    * exhaustion or a permanent failure. True = connected and registered.
    */
  private def attemptConnect(budget: Int): Boolean < (Sync & Async) =
    def loop(attempt: Int): Boolean < (Sync & Async) =
      if closed then false
      else
        openConn().map {
          case Result.Failure(McpConnectError.McpUnsupported(reason)) =>
            closed = true
            onState(McpManager.State.Unsupported(reason))
            false
          case Result.Failure(McpConnectError.McpConnectFailed(err)) =>
            if isPermanent(err) || attempt + 1 >= budget then park(err).andThen(false)
            else backoff(attempt).andThen(loop(attempt + 1))
          case Result.Panic(e) =>
            park(String.valueOf(e.getMessage)).andThen(false)
          case Result.Success(conn) =>
            listAndRegister(conn).map {
              case Result.Success(count) =>
                current = Present(conn)
                parkedReason = Absent
                recordSuccess()
                onState(McpManager.State.Connected(count))
                true
              case Result.Failure(err) =>
                conn.close.andThen {
                  if attempt + 1 >= budget then park(err).andThen(false)
                  else backoff(attempt).andThen(loop(attempt + 1))
                }
              case Result.Panic(e) =>
                conn.close.andThen(park(String.valueOf(e.getMessage)).andThen(false))
            }
        }
    loop(0)
  end attemptConnect

  private def backoff(attempt: Int): Unit < (Sync & Async) =
    val base   = (tuning.backoffBase.toMillis * math.pow(2, attempt)).toLong
      .min(tuning.backoffCap.toMillis)
    val jitter = (base * (0.8 + scala.util.Random.nextDouble() * 0.4)).toLong.max(1)
    sleepUnlessClosed(jitter.millis)

  /** Interruptible sleep: checks the closed flag in slices so stopAll and
    * test teardown don't wait out long intervals.
    */
  private def sleepUnlessClosed(d: Duration): Unit < (Sync & Async) =
    val slice = 100.millis
    def loop(remaining: Long): Unit < (Sync & Async) =
      if closed || remaining <= 0 then ()
      else
        val step = remaining.min(slice.toMillis)
        Async.sleep(step.millis).andThen(loop(remaining - step))
    loop(d.toMillis)

  private def park(reason: String): Unit < Sync =
    Sync.defer {
      parkedReason = Present(reason)
      onState(McpManager.State.Parked(reason))
    }.andThen(spawnParkedProbe())

  /** Every `parkedProbeInterval`, try one full reconnect; success revives
    * the server (upstream `_PARKED_RETRY_INTERVAL` self-probe).
    */
  private def spawnParkedProbe(): Unit < Sync =
    if probing || closed then ()
    else
      probing = true
      Fiber.initUnscoped {
        def loop: Unit < (Sync & Async) =
          sleepUnlessClosed(tuning.parkedProbeInterval).andThen {
            if closed then Sync.defer { probing = false }
            else if !parked then Sync.defer { probing = false }
            else
              openConn().map {
                case Result.Success(conn) =>
                  listAndRegister(conn).map {
                    case Result.Success(count) =>
                      current = Present(conn)
                      parkedReason = Absent
                      probing = false
                      recordSuccess()
                      onState(McpManager.State.Connected(count))
                      spawnKeepalive()
                    case _ => conn.close.andThen(loop)
                  }
                case _ => loop
              }
          }
        loop
      }.unit
  end spawnParkedProbe

  /** One reconnect fiber at a time: closes the dead transport, then runs
    * the reconnect budget; exhaustion parks.
    */
  private[mcp] def triggerReconnect(): Unit < Sync =
    if closed || parked || !reconnectGate.compareAndSet(false, true) then ()
    else
      val old = current
      current = Absent
      onState(McpManager.State.Reconnecting)
      Fiber.initUnscoped {
        (old.map(_.close).getOrElse(()): Unit < (Sync & Async))
          .andThen(attemptConnect(tuning.maxReconnects))
          .andThen(Sync.defer(reconnectGate.set(false)))
      }.unit

  /** Polls for a completed reconnect (the ≤15s wait before retrying once). */
  private def awaitReconnect(budget: Duration): Boolean < (Sync & Async) =
    def loop(remaining: Long): Boolean < (Sync & Async) =
      if current.nonEmpty then true
      else if closed || parked || remaining <= 0 then false
      else Async.sleep(100.millis).andThen(loop(remaining - 100))
    loop(budget.toMillis)

  // --- the guarded request (McpConnection) ---------------------------------

  def request(
      method: String,
      params: Maybe[Value],
      timeout: Maybe[Duration]
  ): Result[String, Value] < (Sync & Async) =
    if closed then Result.fail(s"MCP server '$serverName' is not connected")
    else
      parkedReason match
        case Present(reason) => Result.fail(parkMsg(reason))
        case Absent =>
          breakerState match
            case Present(remaining) if remaining > 0 => Result.fail(breakerMsg(remaining))
            case _ =>
              current match
                case Absent => Result.fail(transportDownMsg)
                case Present(client) =>
                  client.request(method, params, timeout).map {
                    case Result.Success(v) =>
                      recordSuccess()
                      Result.succeed(v)
                    case Result.Panic(e) =>
                      recordFailure()
                      Result.fail(String.valueOf(e.getMessage))
                    case Result.Failure(err) =>
                      if isPermanent(err) then
                        recordFailure()
                        park(err).andThen(Result.fail(err))
                      else if isReconnectable(err, client.alive) then
                        recordFailure()
                        triggerReconnect().andThen(awaitReconnect(tuning.reconnectWait)).map {
                          case false => Result.fail(transportDownMsg)
                          case true =>
                            // Respawned in time: retry the call once.
                            current match
                              case Absent => Result.fail(transportDownMsg)
                              case Present(fresh) =>
                                fresh.request(method, params, timeout).map {
                                  case Result.Success(v) =>
                                    recordSuccess()
                                    Result.succeed(v)
                                  case Result.Failure(e2) =>
                                    recordFailure()
                                    Result.fail(e2)
                                  case Result.Panic(e2) =>
                                    recordFailure()
                                    Result.fail(String.valueOf(e2.getMessage))
                                }
                        }
                      else
                        recordFailure()
                        Result.fail(err)
                  }
  end request

  def notify(method: String, params: Maybe[Value]): Unit < (Sync & Async) =
    current.map(_.notify(method, params)).getOrElse(())

  def close: Unit < (Sync & Async) =
    Sync.defer { closed = true }.andThen(current.map(_.close).getOrElse(()))

  def destroyNow(): Unit =
    closed = true
    current.foreach(_.destroyNow())

  // --- keepalive -----------------------------------------------------------

  /** `ping` on a cadence; `-32601`-style rejections latch a per-connection
    * `tools/list` fallback; any other failure is confirmed with `tools/list`
    * before the session is declared dead and reconnected.
    */
  private def spawnKeepalive(): Unit < Sync =
    if keepaliveRunning || closed then ()
    else
      keepaliveRunning = true
      Fiber.initUnscoped {
        def confirmDead(client: McpConnection): Unit < (Sync & Async) =
          client.request("tools/list", Absent,
            Present(McpConfig.duration(cfg.connectTimeoutSeconds))).map {
            case Result.Success(_) => ()
            case _                 => triggerReconnect()
          }
        def tick: Unit < (Sync & Async) =
          sleepUnlessClosed(McpConfig.duration(cfg.keepaliveIntervalSeconds)).andThen {
            if closed then Sync.defer { keepaliveRunning = false }
            else
              val step: Unit < (Sync & Async) =
                if parked || reconnectGate.get() then ()
                else
                  current match
                    case Absent => ()
                    case Present(client) =>
                      if pingUnsupported then confirmDead(client)
                      else
                        client.request("ping", Absent, Present(tuning.keepaliveRpcTimeout)).map {
                          case Result.Success(_) => ()
                          case Result.Failure(err) if isMethodNotFound(err) =>
                            pingUnsupported = true
                            confirmDead(client)
                          case _ => confirmDead(client)
                        }
              step.andThen(tick)
          }
        tick
      }.unit
  end spawnKeepalive

  /** `notifications/tools/list_changed`: refresh the registration in place
    * (upstream re-lists in the background).
    */
  private[mcp] def refreshTools(): Unit < Sync =
    Fiber.initUnscoped {
      current match
        case Absent => ()
        case Present(client) =>
          listAndRegister(client).map {
            case Result.Success(count) => Sync.defer(onState(McpManager.State.Connected(count)))
            case _                     => ()
          }
    }.unit
end McpServerHandle
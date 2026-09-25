// SPDX-FileCopyrightText: 2026 David Greco <greco@acm.org>
//
// SPDX-License-Identifier: Apache-2.0

package apollo.mcp

import apollo.util.Jx
import apollo.util.Jx.*
import kyo.*
import kyo.Structure.Value

/** The reliability ladder, exercised against an in-memory fake connection:
  * circuit breaker open/half-open/close, dead-transport reconnect with
  * retry-once, budget exhaustion parking + self-probe revival, immediate
  * parking on permanent failures, and the keepalive ping→tools/list
  * fallback.
  */
class McpReliabilitySuite extends munit.FunSuite:

  private def run[A](v: A < (Sync & Async)): A =
    import AllowUnsafe.embrace.danger
    KyoApp.Unsafe.runAndBlock(30.seconds)(v).getOrThrow

  private final class FakeConn(val serverName: String) extends McpConnection:
    val calls = new java.util.concurrent.ConcurrentLinkedQueue[String]()
    @volatile var aliveFlag                                  = true
    @volatile var stdioFlag                                  = true
    @volatile var responder: String => Result[String, Value] = _ => Result.succeed(Jx.obj())
    def alive: Boolean          = aliveFlag
    def stdio: Boolean          = stdioFlag
    def initializeResult: Value = Jx.obj()
    def request(m: String, p: Maybe[Value], t: Maybe[Duration]): Result[String, Value] < (Sync & Async) =
      Sync.defer {
        calls.add(m)
        responder(m)
      }
    def notify(m: String, p: Maybe[Value]): Unit < (Sync & Async) = ()
    def close: Unit < (Sync & Async)                              = Sync.defer { aliveFlag = false }
    def destroyNow(): Unit                                        = aliveFlag = false
  end FakeConn

  private def cfg(name: String, keepaliveSeconds: Double = 3600.0): McpServerConfig =
    McpServerConfig(
      name = name, enabled = true, command = Present("x"), args = Nil, env = Map.empty,
      cwd = Absent, url = Absent, headers = Nil, transport = Absent, auth = Absent,
      connectTimeoutSeconds = 5.0, toolTimeoutSeconds = 5.0,
      keepaliveIntervalSeconds = keepaliveSeconds, include = Absent, exclude = Nil,
      resourceTools = true, promptTools = true, untrusted = false,
      oauthClientId = Absent, oauthClientSecret = Absent, oauthScopes = Nil,
      oauthRedirectPort = 0
    )

  private def mkHandle(
      config: McpServerConfig,
      tuning: McpTuning,
      opens: () => Result[McpConnectError, McpConnection]
  ): (McpServerHandle, java.util.concurrent.ConcurrentLinkedQueue[McpManager.State]) =
    val states = new java.util.concurrent.ConcurrentLinkedQueue[McpManager.State]()
    val handle = new McpServerHandle(
      config, tuning,
      openConn = () => Sync.defer(opens()),
      listAndRegister = _ => Result.succeed(1),
      onState = st => { states.add(st); () }
    )
    (handle, states)

  private def eventually(deadlineMs: Long = 5000)(check: => Boolean): Unit =
    val end = java.lang.System.currentTimeMillis() + deadlineMs
    while !check && java.lang.System.currentTimeMillis() < end do Thread.sleep(25)
    assert(check, s"condition not met within ${deadlineMs}ms")

  test("breaker: opens after threshold, fails fast, half-open probe closes it") {
    val conn = new FakeConn("srv")
    conn.responder = _ => Result.fail("MCP server 'srv' error 1: boom")
    val tuning        = McpTuning(breakerThreshold = 3, breakerCooldown = 300.millis)
    val (handle, _)   = mkHandle(cfg("srv"), tuning, () => Result.succeed(conn))
    run(handle.start())

    (1 to 3).foreach { _ =>
      val r = run(handle.request("tools/call", Absent, Absent))
      assert(r.failure.getOrElse("").contains("boom"))
    }
    // Breaker open: fails fast with the upstream message shape.
    val open = run(handle.request("tools/call", Absent, Absent))
    val msg  = open.failure.getOrElse("")
    assert(msg.contains("unreachable after 3 consecutive failures"), msg)
    assert(msg.contains("Do NOT retry this tool yet"), msg)
    // The gated call never reached the server.
    assertEquals(conn.calls.size, 3)

    Thread.sleep(350) // past the cooldown: half-open lets one probe through
    conn.responder = _ => Result.succeed(Jx.obj("ok" -> Jx.bool(true)))
    assert(run(handle.request("tools/call", Absent, Absent)).isSuccess)
    assert(run(handle.request("tools/call", Absent, Absent)).isSuccess)
  }

  test("dead transport: reconnect + transparent retry-once") {
    val conn1 = new FakeConn("srv")
    val conn2 = new FakeConn("srv")
    conn2.responder = _ => Result.succeed(Jx.obj("via" -> Jx.str("conn2")))
    val openCount = new java.util.concurrent.atomic.AtomicInteger(0)
    val (handle, states) = mkHandle(cfg("srv"), McpTuning(backoffBase = 10.millis), () =>
      if openCount.incrementAndGet() == 1 then Result.succeed(conn1)
      else Result.succeed(conn2)
    )
    run(handle.start())
    // The live transport dies mid-flight.
    conn1.aliveFlag = false
    conn1.responder = _ => Result.fail("MCP server 'srv': write failed (Broken pipe)")

    val r = run(handle.request("tools/call", Absent, Absent))
    assert(r.isSuccess, r.toString)
    assertEquals(r.getOrElse(Jx.obj()).field("via").asStr, Present("conn2"))
    assertEquals(openCount.get(), 2)
    import scala.jdk.CollectionConverters.*
    assert(states.asScala.exists(_ == McpManager.State.Reconnecting), states.asScala.toList)
  }

  /** Upstream #106546: the child was alive when the call was dispatched, so
    * the action may already have been applied — reconnect, but do not replay.
    */
  test("stdio death mid-call: an in-flight tools/call is not replayed") {
    val conn1 = new FakeConn("srv")
    val conn2 = new FakeConn("srv")
    conn2.responder = _ => Result.succeed(Jx.obj("via" -> Jx.str("conn2")))
    val openCount = new java.util.concurrent.atomic.AtomicInteger(0)
    val (handle, _) = mkHandle(cfg("srv"), McpTuning(backoffBase = 10.millis), () =>
      if openCount.incrementAndGet() == 1 then Result.succeed(conn1) else Result.succeed(conn2)
    )
    run(handle.start())
    conn1.responder = _ =>
      conn1.aliveFlag = false // dies *after* dispatch
      Result.fail("MCP server 'srv': write failed (Broken pipe)")

    val msg = run(handle.request("tools/call", Absent, Absent)).failure.getOrElse("")
    assert(msg.contains("may or may not have been applied"), msg)
    assert(conn2.calls.isEmpty, s"the call was replayed: ${conn2.calls}")
    // The transport is still rebuilt for later calls.
    eventually()(openCount.get() == 2)
  }

  test("stdio death mid-call: a read-only method is still retried once") {
    val conn1 = new FakeConn("srv")
    val conn2 = new FakeConn("srv")
    conn2.responder = _ => Result.succeed(Jx.obj("via" -> Jx.str("conn2")))
    val openCount = new java.util.concurrent.atomic.AtomicInteger(0)
    val (handle, _) = mkHandle(cfg("srv"), McpTuning(backoffBase = 10.millis), () =>
      if openCount.incrementAndGet() == 1 then Result.succeed(conn1) else Result.succeed(conn2)
    )
    run(handle.start())
    conn1.responder = _ =>
      conn1.aliveFlag = false
      Result.fail("MCP server 'srv': write failed (Broken pipe)")

    val r = run(handle.request("tools/list", Absent, Absent))
    assertEquals(r.getOrElse(Jx.obj()).field("via").asStr, Present("conn2"))
  }

  test("an HTTP server keeps its retry when a call dies in flight") {
    val conn1 = new FakeConn("srv")
    val conn2 = new FakeConn("srv")
    conn1.stdioFlag = false
    conn2.stdioFlag = false
    conn2.responder = _ => Result.succeed(Jx.obj("via" -> Jx.str("conn2")))
    val openCount = new java.util.concurrent.atomic.AtomicInteger(0)
    val httpCfg = cfg("srv").copy(command = Absent, url = Present("https://example.test/mcp"))
    val (handle, _) = mkHandle(httpCfg, McpTuning(backoffBase = 10.millis), () =>
      if openCount.incrementAndGet() == 1 then Result.succeed(conn1) else Result.succeed(conn2)
    )
    run(handle.start())
    conn1.responder = _ =>
      conn1.aliveFlag = false
      Result.fail("MCP server 'srv': session expired")

    val r = run(handle.request("tools/call", Absent, Absent))
    assertEquals(r.getOrElse(Jx.obj()).field("via").asStr, Present("conn2"))
  }

  test("reconnect budget exhaustion parks; self-probe revives") {
    val conn1 = new FakeConn("srv")
    val conn2 = new FakeConn("srv")
    conn2.responder = _ => Result.succeed(Jx.obj("via" -> Jx.str("conn2")))
    val healthy   = new java.util.concurrent.atomic.AtomicBoolean(true)
    val openCount = new java.util.concurrent.atomic.AtomicInteger(0)
    val tuning = McpTuning(
      maxReconnects = 2, backoffBase = 10.millis, backoffCap = 20.millis,
      parkedProbeInterval = 150.millis, reconnectWait = 500.millis
    )
    val (handle, _) = mkHandle(cfg("srv"), tuning, () =>
      val n = openCount.incrementAndGet()
      if n == 1 then Result.succeed(conn1)
      else if healthy.get() then Result.succeed(conn2)
      else Result.fail(McpConnectError.McpConnectFailed("connection refused"))
    )
    run(handle.start())
    healthy.set(false)
    conn1.aliveFlag = false
    conn1.responder = _ => Result.fail("MCP server 'srv': connection reset")

    run(handle.request("tools/call", Absent, Absent)) // triggers doomed reconnects
    eventually()(handle.parked)
    val parked = run(handle.request("tools/call", Absent, Absent))
    assert(parked.failure.getOrElse("").contains("parked"), parked.toString)

    healthy.set(true) // the server comes back; the self-probe should revive
    eventually()(!handle.parked)
    eventually()(run(handle.request("tools/call", Absent, Absent)).isSuccess)
  }

  test("permanent failures park immediately without reconnect attempts") {
    val conn = new FakeConn("srv")
    val openCount = new java.util.concurrent.atomic.AtomicInteger(0)
    val (handle, _) = mkHandle(cfg("srv"), McpTuning(parkedProbeInterval = 10.seconds), () =>
      openCount.incrementAndGet()
      Result.succeed(conn)
    )
    run(handle.start())
    conn.responder = _ => Result.fail("MCP server 'srv': HTTP 401 — unauthorized")
    val r = run(handle.request("tools/call", Absent, Absent))
    assert(r.failure.getOrElse("").contains("401"), r.toString)
    assert(handle.parked)
    assertEquals(openCount.get(), 1) // no reconnect ladder for permanent failures
  }

  test("keepalive: ping unsupported latches the tools/list fallback") {
    val conn = new FakeConn("srv")
    conn.responder = {
      case "ping" => Result.fail("MCP server 'srv' error -32601: Method not found")
      case _      => Result.succeed(Jx.obj("tools" -> Jx.arr()))
    }
    val (handle, _) = mkHandle(cfg("srv", keepaliveSeconds = 5.0).copy(keepaliveIntervalSeconds = 0.2),
      McpTuning(keepaliveRpcTimeout = 1.second), () => Result.succeed(conn))
    run(handle.start())
    import scala.jdk.CollectionConverters.*
    eventually()(conn.calls.asScala.count(_ == "tools/list") >= 2)
    // The -32601 latched after the first ping; later ticks list directly.
    assert(conn.calls.asScala.count(_ == "ping") <= 2, conn.calls.asScala.toList)
    assert(handle.alive)
    run(handle.close)
  }

  test("keepalive: failed confirm declares dead and reconnects") {
    val conn1 = new FakeConn("srv")
    val conn2 = new FakeConn("srv")
    val openCount = new java.util.concurrent.atomic.AtomicInteger(0)
    val (handle, _) = mkHandle(cfg("srv").copy(keepaliveIntervalSeconds = 0.2),
      McpTuning(keepaliveRpcTimeout = 1.second, backoffBase = 10.millis), () =>
        if openCount.incrementAndGet() == 1 then Result.succeed(conn1)
        else Result.succeed(conn2)
    )
    run(handle.start())
    conn1.responder = _ => Result.fail("MCP server 'srv': connection reset") // ping AND confirm fail
    eventually()(openCount.get() >= 2)
    eventually()(run(handle.request("tools/call", Absent, Absent)).isSuccess)
    run(handle.close)
  }
end McpReliabilitySuite
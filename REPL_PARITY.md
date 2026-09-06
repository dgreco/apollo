# REPL Slash-Command Parity — Status & Resume Point

Snapshot of the effort to bring apollo's interactive REPL slash commands toward
parity with the NousResearch Hermes agent (`hermes_cli/commands.py`). Written so
the work can be resumed later without re-deriving the context.

**As of:** 2026-09-05 · **Branch:** `main` · **Last commit:** `d67f7d0`
**Verified:** JVM full suite (219 tests) + Scala Native suites green; CI pipelines
**168–178** all green (compile / test:jvm / integration:compose). Opt-in
`display.async_input` mode is unit-tested but its interactive terminal behavior
is pending hands-on TTY validation.

apollo's REPL went from **12 commands (2 of them dead stubs)** to **~54 real,
tested commands**. **All four resume-roadmap items are now done** (concurrent-input
TUI, Agent test seam, subsystems, and REPL↔gateway `/handoff`). What remains
un-built is only the commands with no real apollo foundation (`/plugins`,
`/suggestions`, `/journey`) and the hands-on TTY validation of `display.async_input`
(needs a real terminal). See "Not built" and "Resume roadmap".

---

## Built (all real, CI-green, both platforms)

| Group | Commands |
|---|---|
| Meta | `/help`, `/version`·`/v`, `/whoami` |
| Model | `/model`, `/reasoning`, `/reasoning-display`, `/verbose` |
| Session | `/status`·`/history` (real token/context %), `/usage`, `/config`, `/profile`, `/reset`·`/new`, `/clear`, `/redraw`, `/title`, `/compress`·`/compact`, `/save`, `/prompt`·`/compose`, `/retry`, `/copy`, `/image`, `/sessions`, `/resume`, `/branch`·`/fork` |
| Work | `/plan`, `/init`, `/diff`, `/loop`, `/bg`, `/agents`·`/tasks`, `/stop`, `/review`, `/goal`, `/queue`, `/moa`, `/learn`, `/heartbeat`·`/hb`, `/steer` (queues for next turn; mid-turn pending the TUI layer), `/worktree`, `/snapshot`, `/rollback` |
| Tools & services | `/tools`, `/skills`, `/reload-skills`, `/mcp`, `/cron`, `/memory` |
| Approvals | `/yolo`, `/approvals` |
| Exit | `/quit`·`/exit`·`/q` |

### Build history (commits)
- `61b018d` — fixed the `/compress` & `/yolo` stubs; honest `/status`; added `/config`, `/approvals`, `/mcp`, `/cron`, `/memory`, `/sessions`, `/resume`, `/save`, `/retry`, `/copy`, `/version`.
- `6f57f6d` — `/clear`, `/redraw`, `/title`, `/profile`, `/whoami`, `/usage`, `/diff`, `/reload-skills`, `/plan`, `/init`; subsystems `/branch`, `/bg`+`/agents`+`/stop`, `/loop`, `/review`.
- `86793e8` — `/image` (multimodal), `/worktree`, `/snapshot`.
- `f485d83` — `/rollback` (git checkpoints), `/prompt` (inline multi-line compose).
- `ab49ce1` + `e97dc48` — `/goal`, `/queue`, `/moa` (mixture-of-agents), `/learn`.
- `e763404` — `/heartbeat` via a race-based idle loop.

---

## Not built — and why (deliberately NOT stubbed)

### Blocked by architecture
| Command(s) | Missing foundation |
|---|---|
| `/steer` (mid-turn), live `/queue`-while-running | **Built (opt-in), pending TTY validation.** Set `display.async_input: true` — turns run on a background fiber, a single persistent `readLine` stays live, and output streams above via `LineEditor.printAbove` (JLine). You can then type `/steer`, `/stop`, `/queue`, or plain follow-ups *during* a turn. Logic + line-buffering are unit-tested; the interactive terminal behavior is JVM/JLine-only and **not** headlessly verifiable — validate on a real terminal. Native has no concurrent-input TUI (printAbove = plain println). |
| ~~`/handoff`~~ | **Built** — a filesystem control channel (`apollo.session.HandoffStore`): `/handoff <platform>` writes a pending-handoff record; `SessionHub.entry` consumes it (once) when that platform's next session is created and seeds it from the handed-off transcript. End-to-end tested (`HandoffSuite`). |
| ~~`/blueprint`~~, ~~`/kanban`~~, ~~`/curator`~~ | **Built** — `/blueprint` (templates → cron jobs), `/kanban` (local board), `/curator` (skill list/archive/restore). |
| `/plugins`, `/suggestions`, `/journey` | Declined — no real foundation: apollo has no plugin loader (`/plugins`) or suggestion engine (`/suggestions`), and `/journey` has no learning-log store (would just duplicate `/sessions`). Not stubbed. |

### Out of apollo's scope
Nous backend (`/subscription`, `/topup`, `/insights`, `/update`, `/debug`),
audio (`/voice`, `/wake`), browser CDP (`/browser`), and rich-TUI chrome toggles
(`/skin`, `/statusbar`, `/battery`, `/indicator`, `/timestamps`, `/footer`,
`/focus`, `/personality`, `/palette`, `/pet`, `/hatch`).

---

## Verification caveats (below the bar the rest of the work met)

1. **`/heartbeat` interactive behavior is unverified.** The race *mechanism*
   (`Async.race(readLine, timer)`) was confirmed by a throwaway spike, and
   `parseInterval` is unit-tested, but whether cancelling a blocked `readLine`
   on a real JLine/native-termios TTY leaves the terminal clean is **not**
   testable from CI. **To validate by hand:** run `apollo`, then
   `/hb every 20s say hi`, confirm it fires cleanly and the prompt still works
   afterward; `/hb clear` to stop.
2. ~~No mock-transport seam in `Agent`.~~ **Resolved:** the localhost mock
   chat-completions server pattern (`NudgeIntegrationSuite`, and now
   `AgentSteerSuite`) IS the seam — a real `Agent` runs against a mock HTTP
   server, so turn-level behavior is testable end-to-end without an Agent ctor
   change. Steer injection is verified this way on both platforms.

---

## Resume roadmap (in priority order)

Each is a real project, not a command — scope and build with tests where the
architecture allows, and expect the concurrent-input work to need hands-on TTY
validation.

1. ~~Concurrent-input TUI layer~~ — **built (opt-in), both platforms.**
   `display.async_input: true` runs turns on a fiber with a persistent
   `readLine` and `LineEditor.printAbove` output; enables mid-turn `/steer`,
   `/stop`, `/queue`. JVM uses JLine's `printAbove`; Native uses a hand-rolled
   termios redraw (erase line → print → reprint prompt+buffer, cursor restored;
   serialized with the edit loop via `ioLock`, shared line state via `@volatile`).
   **Still needs hands-on TTY validation on BOTH platforms** (not headlessly
   testable). On Native there's an unverified assumption that the blocking
   `readByte` on the main fiber doesn't starve the turn fiber — the localhost
   HTTP E2E tests passing on Native suggest concurrent fibers with blocking I/O
   work, but confirm interactively. Code: `Repl.asyncLoop`/`runAsyncTurn`/
   `asyncCallbacks`; `LineEditor.printAbove` (both `PlatformEditor`s).
2. ~~Agent mock-transport seam~~ — **done** (the localhost-mock harness serves
   this; `AgentSteerSuite` uses it). Agent-side steer is built and tested.
3. ~~REPL↔gateway IPC~~ — **done** via a filesystem control channel
   (`apollo.session.HandoffStore`): `/handoff <platform>` → pending record →
   `SessionHub.entry` adopts it into the platform's next session. End-to-end
   tested (`HandoffSuite`), both platforms.
4. **Subsystems** — the buildable ones are done: `/blueprint` (cron templates),
   `/kanban` (local board), `/curator` (skill archive/restore). The rest need
   foundations apollo doesn't have and were declined, not stubbed: `/plugins`
   (no plugin loader), `/suggestions` (no suggestion engine), `/journey` (no
   learning-log; would duplicate `/sessions`). Building any of those means
   building the subsystem first.

---

## Where the code lives

- `shared/src/main/scala/apollo/cli/Repl.scala` — `handleSlash` (dispatch),
  `loop`/`nextTick` (the heartbeat race loop), and all `doX` command helpers;
  `Repl` companion holds `Heartbeat` + `Tick`.
- `shared/src/main/scala/apollo/cli/ReplCommands.scala` — pure, unit-tested
  rendering/parsing helpers (`ReplCommandsSuite`).
- `shared/src/main/scala/apollo/cli/BackgroundSessions.scala` — `/bg` registry.
- `shared/src/main/scala/apollo/agent/Agent.scala` — `resumeSession` (`/resume`,
  `/branch`), `requestCompress` (`/compress`), usage accessors (`/status`).
  **Steer injection point** for a future `/steer`: after `runToolRound` returns,
  before the loop recurses (around the `resultMsg` append).
- `shared/src/main/scala/apollo/tools/Approval.scala` — runtime `/yolo` +
  `/approvals` overrides.
- Tests: `shared/src/test/scala/apollo/ReplCommandsSuite.scala`,
  `ApprovalPolicySuite.scala`.

CI: `.gitlab-ci.yml` (needs **JDK 25+** — kyo RC6 macros; image
`sbtscala/scala-sbt:eclipse-temurin-25.0.4_7_2.x`). Watch runs with
`glab` against `gitlab.davidgreco.it`.

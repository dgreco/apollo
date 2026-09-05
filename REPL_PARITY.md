# REPL Slash-Command Parity — Status & Resume Point

Snapshot of the effort to bring apollo's interactive REPL slash commands toward
parity with the NousResearch Hermes agent (`hermes_cli/commands.py`). Written so
the work can be resumed later without re-deriving the context.

**As of:** 2026-09-05 · **Branch:** `main` · **Last commit:** `e763404`
**Verified:** JVM full suite (217 tests) + Scala Native suites green; CI pipelines
**168–176** all green (compile / test:jvm / integration:compose).

apollo's REPL went from **12 commands (2 of them dead stubs)** to **~50 real,
tested commands**. Genuine remaining parity is now blocked by missing
*architecture*, not by effort — see "Not built" and "Resume roadmap".

---

## Built (all real, CI-green, both platforms)

| Group | Commands |
|---|---|
| Meta | `/help`, `/version`·`/v`, `/whoami` |
| Model | `/model`, `/reasoning`, `/reasoning-display`, `/verbose` |
| Session | `/status`·`/history` (real token/context %), `/usage`, `/config`, `/profile`, `/reset`·`/new`, `/clear`, `/redraw`, `/title`, `/compress`·`/compact`, `/save`, `/prompt`·`/compose`, `/retry`, `/copy`, `/image`, `/sessions`, `/resume`, `/branch`·`/fork` |
| Work | `/plan`, `/init`, `/diff`, `/loop`, `/bg`, `/agents`·`/tasks`, `/stop`, `/review`, `/goal`, `/queue`, `/moa`, `/learn`, `/heartbeat`·`/hb`, `/worktree`, `/snapshot`, `/rollback` |
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
| `/steer`, live `/queue`-while-running | A **concurrent-input TUI**: a reader active *during* a streaming turn (bottom input line + redraw). apollo's REPL is a single blocking `readLine` loop; there is no input during a turn. Non-verifiable headlessly (needs a real TTY). |
| `/handoff` | **REPL↔gateway IPC** — the REPL can't hand a live session to a separate `apollo gateway` process. |
| `/kanban`, `/plugins`, `/curator`, `/blueprint`, `/journey`, `/suggestions` | Whole subsystems apollo lacks: board model, plugin loader, skill-graph, suggestion engine. |

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
2. **No mock-transport seam in `Agent`.** `Agent.runTurn` selects a real
   `WireTransport` internally, so turn-level behavior (e.g. an Agent-side
   `/steer` injection after a tool round) cannot be unit-tested without first
   building a fake-provider harness. `AgentSuite.scala` currently only covers
   `Alternation`/`Compression`.

---

## Resume roadmap (in priority order)

Each is a real project, not a command — scope and build with tests where the
architecture allows, and expect the concurrent-input work to need hands-on TTY
validation.

1. **Concurrent-input TUI layer** — bottom input line + output-above redraw,
   for both JLine (JVM) and the native termios editor. Unlocks `/steer` and
   live `/queue`-while-running. Highest leverage; also the hardest and the least
   headless-verifiable.
2. **Agent mock-transport seam** — inject a fake `WireTransport` (e.g. via
   `ToolContext` or an Agent ctor param) so `runTurn` is unit-testable. Cheap,
   high value; unblocks testing steer/tool-round behavior.
3. **REPL↔gateway IPC** — a control channel (socket/file) so `/handoff` can pass
   a live session to a running `apollo gateway`.
4. **Subsystems** — pick per need: plugin loader (`/plugins`), board model
   (`/kanban`), suggestion engine (`/suggestions`), skill-graph (`/journey`),
   skill maintenance (`/curator`), automation templates (`/blueprint`).

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

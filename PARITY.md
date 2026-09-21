# apollo vs Hermes — Feature Parity

A sourced map of where apollo stands against the upstream **NousResearch Hermes
agent** (`github.com/NousResearch/hermes-agent`). First audited at commit
`089bb32`; **re-audited against `main` @ `5bb314f` (2026-09-21)**, which is three
releases later — v0.21.1 (`v2026.9.7`), v0.21.2 (`v2026.9.11`), v0.21.3
(`v2026.9.14`) — and ~7,700 commits of upstream movement. Companion to
`REPL_PARITY.md` (which covers the REPL slash commands specifically).

**TL;DR.** apollo faithfully reimplements Hermes's **core** (agent loop, the four
wire protocols, MCP client, a few gateways, sessions/memory/skills, the REPL).
Hermes has since grown into a large **platform** (~11.8k files, 381 plugins, an
Electron desktop app, browser/computer-use, ~25 chat platforms, billing). The
gaps are almost all *breadth*, and most are out of apollo's terminal-first,
dependency-light, JVM + Scala-Native scope by design.

---

## At parity (the spine)

- **Wire protocols:** all four — `chat_completions`, `anthropic_messages`,
  `codex_responses`, `bedrock_converse` — with ~52 provider profiles.
- **Agent loop:** compression (two-phase), self-improvement nudges, alternation
  repair, retries + backoff, `fallback_providers`, interrupts, delegation,
  grace call, persist-before-execute, steer/goal/queue/heartbeat/moa/review/bg
  (REPL).
- **MCP client:** stdio + streamable-HTTP transports, OAuth 2.1 + PKCE,
  reliability supervisor, dynamic tool registration.
- **Sessions / memory / skills:** JSONL transcripts + JSON index (FTS5 search on
  the JVM, scan on Native), `MEMORY.md`/`USER.md`, skills scan + `skill_manage`.
- **Gateways:** Telegram, Discord, Slack, OpenAI-compatible API server, webhook,
  cron.
- **REPL:** ~54 commands, live completion menu, editor shortcuts, welcome banner,
  status line (see `REPL_PARITY.md`).

---

## Group A — core-agent gaps (in-scope; worth doing)

Small, in-philosophy items that make apollo a stronger *agent* without turning it
into a platform. Roughly ordered by value/effort.

| Gap | Notes |
|---|---|
| ~~**`vision_analyze` tool**~~ | **Done** — analyzes an image file via an injected `VisionRunner`; new `vision` toolset. |
| ~~**`execute_code` tool**~~ | **Done** — runs a snippet per language (python/node/ruby/bash/sh) via the terminal backend; `code_execution` toolset. |
| ~~**`tool_search`**~~ | **Done** — keyword search over all registered tools (built-in + MCP); `tool_search` toolset. |
| ~~**Background auto-review**~~ | **Done** — `agent.auto_review` forks a background reviewer every `auto_review_interval` REPL turns that *actually saves* durable memories/skills via the memory/skill_manage tools (the nudges still only remind). Opt-in (spends a model call). |
| ~~**MCP: sampling + elicitation**~~ | **Done (stdio + streamable-HTTP)** — server-initiated `sampling/createMessage` (runs a model call) and `elicitation/create` (asks the user / declines), handled by both `McpClient` (stdio) and `McpHttpClient` (a request arriving on the SSE stream is dispatched and its JSON-RPC reply POSTed back). Still open: **MCP server mode** (apollo *as* a server) and legacy HTTP+SSE transport — both larger. |
| ~~**Provider OAuth**~~ | **Copilot + Qwen + Vertex/Azure** — `apollo auth copilot login` and `apollo auth qwen login` (OAuth device-code; Qwen adds PKCE + token refresh, targeting the token's `resource_url`). Vertex and Azure Entra resolve via `model.key_cmd` (`gcloud auth print-access-token` / `az account get-access-token`). ⚠️ Copilot/Qwen constants are reverse-engineered; the live flows need hands-on validation. Still open: ChatGPT/Codex (loopback-PKCE broker), xAI (API-key only), native Gemini, MoA. |
| ~~**Bedrock streaming**~~ | **Done** — `ConverseStream` decoded via `apollo.util.EventStream`; selected by `model.streaming`. |
| ~~**Guards**~~ | **Done** — repetition guard (breaks a turn on `agent.repetition_limit` identical tool calls), empty-response guard (re-prompts `agent.empty_response_retries` times, then exits `empty_response`), and Anthropic prompt-cache breakpoints on system+tools+rolling-last-message (`prompt_cache.enabled`). |
| ~~**More terminal backends**~~ | **Done** — added `singularity`/`apptainer` (HPC container exec) and a generic `exec`/`custom` backend that prefixes commands with any user-configured sandbox CLI argv (podman, kubectl, nsjail, or a cloud sandbox's own CLI — modal/daytona/vercel — reached this way rather than via each vendor SDK). Now: local/docker/ssh/singularity/exec. |

## Group B — breadth features (bigger; arguably in-scope)

- Vision analysis (input) done in Group A. **`image_generate`** done — a tool
  hitting an OpenAI-compatible `/images/generations` endpoint (`image.*` config),
  saving the PNG. Video generation still open (niche/heavy).
- ~~**Browser automation**~~ **done** — a `browser` tool driving a real headless
  Chrome over the DevTools Protocol (CDP) on a WebSocket (works JVM + Native):
  navigate, read content/visible-text, run JS, click a selector, screenshot.
  Connects to a running Chrome via `CHROME_CDP_URL` or launches one when
  `browser.enabled`. (Hermes ships several backends; apollo uses CDP directly,
  no SDK.)
- ~~**Skills Hub**~~ **done (install/search)** — `apollo skills install <git-url|owner/repo|dir>`
  (git clone / local copy) and `apollo skills search <q>` over a catalog
  (`skills.hub_catalog_url`). Ledger/linter/curate still open.
- **More gateway platforms** — **Matrix, WhatsApp Cloud, Twilio SMS, MS Teams,
  and iMessage done**, alongside Telegram/Discord/Slack. Matrix is HTTP `/sync`
  long-poll + send; WhatsApp Cloud, Twilio SMS, and Teams (Bot Framework, with an
  Azure-AD client-credentials bearer) are inbound-webhook servers that ACK
  immediately and reply out-of-band via REST; iMessage (macOS, `IMESSAGE_ENABLED`)
  polls the Messages `chat.db` via `sqlite3` and sends via `osascript`.
- **Email (IMAP/SMTP)** — **done, JVM build**: poll an IMAP INBOX for unseen
  messages, run each through the agent, reply over SMTP (`EMAIL_IMAP_HOST` /
  `EMAIL_USER` / `EMAIL_PASSWORD`, TLS-aware port defaults). The protocol layer
  is pure/shared and tested on both platforms; the TLS socket I/O uses the JDK's
  `javax.net.ssl` (no new dependency), so on **Native** — which has no TLS-socket
  support — it degrades to a clear "JVM-only" error (a platform gap alongside the
  line editor / session-search backend).

## Group C — platform features

Several Group-C items have since been **pulled into apollo** (mirroring Hermes's
design, each CI-green JVM + Native unless noted):

- ~~**ACP editor integration**~~ — `apollo acp`, a JSON-RPC/stdio Agent Client
  Protocol server (Zed/VS Code/JetBrains); streams `session/update` chunks.
- ~~**LSP**~~ — an LSP *client*: `lsp` tool + `apollo lsp list` spawn language
  servers (pyright/gopls/rust-analyzer/…) and surface diagnostics.
- ~~**voice / TTS / transcription**~~ — `text_to_speech` + `transcribe` tools
  (OpenAI-compatible audio APIs / macOS `say`). Wake-word is **not** done (needs
  a cross-platform audio-capture + ONNX pipeline apollo has no path to).
- ~~**computer-use**~~ — `computer_use` tool (screenshot/click/type via macOS
  `screencapture`/`cliclick`; Hermes's cua-driver substituted natively).
- ~~**observability (OTLP)**~~ — content-free OTLP/HTTP turn+tool traces;
  `apollo monitoring status`.
- ~~**secrets managers**~~ — 1Password (`op`), Bitwarden (`bws`), command sources
  resolved into the env at startup; `apollo secrets`.
- ~~**checkpoints / git-safety**~~ — automatic pre-edit shadow-ref checkpoints
  (`checkpoints.enabled`) + manual `/rollback`.
- ~~**kanban**~~ — now agent-facing (`kanban` tool) over the shared local board.

Still out of scope (a whole subsystem each, or off apollo's terminal-first,
dependency-light remit): plugins ecosystem · Electron desktop app + web
dashboard + GUI TUI · billing/credits/pricing · egress proxy / credential
firewall · hosted rooms / multi-agent collab · learning-journey / pets /
achievements · suggestion / blueprint engine · context engine · wake-word /
always-listening · enterprise connectors (Feishu, MS Graph, Spotify, Home
Assistant, X search). These are **not** pursued unless apollo's remit changes
from "core agent" to "platform".

---

## Group A & B status

**All Group-A items and their follow-ups are built** (each CI-green, JVM + Native):
`vision_analyze`, Bedrock ConverseStream streaming, `execute_code`, `tool_search`,
MCP sampling/elicitation (**stdio + streamable-HTTP**), provider OAuth (**Copilot +
Qwen**, plus Vertex/Azure via `model.key_cmd`), the **guards** (repetition /
empty-response / prompt-cache), **background auto-review**, and **more terminal
backends** (singularity + generic `exec`).

**Group B (breadth) is built too**: `image_generate`, **`video_generate`**, Skills
Hub, **browser automation** (CDP over WebSocket), and gateway platforms **Matrix,
WhatsApp Cloud, Twilio SMS, MS Teams, iMessage, and email** (email is JVM-only —
see above).

Still open (deliberately, or larger): MCP **server mode** + legacy HTTP+SSE
transport; OAuth for **ChatGPT/Codex**, **xAI**, native Gemini, MoA; and live
validation of the reverse-engineered Copilot/Qwen and Bedrock-stream flows.
Group C stays out of scope (platform, not core agent).

> **Codex OAuth is cheaper than this note used to assume.** The `openai-codex`
> profile is marked unsupported for wanting a "ChatGPT OAuth broker", but
> upstream's new `auth.codex_login_flow` documents **device code as the
> default**, with browser/loopback-PKCE only as the fallback for orgs that
> disable the device grant. apollo already implements device-code twice
> (Copilot, Qwen), so this is now the cheapest remaining Group-A item.

---

## Realignment: 2026-09-21 (upstream `main` @ `5bb314f`)

The re-audit found the **contract intact** — today's 2,267-line upstream
`cli-config.yaml.example` parses and reads correctly (the fixture under
`shared/src/test/resources/` was refreshed to it, and `UpstreamConfigCompatSuite`
now asserts the post-0.21 keys too). These behavioural drifts were found and
**fixed**:

| Drift | Fix |
|---|---|
| A stdio MCP server dying **mid-call** made apollo reconnect and **replay the tool call** — a non-idempotent action could be applied twice (upstream #106546). | `McpServerHandle.request` now distinguishes "dead before dispatch" (safe: retried once) from "died in flight" (returns an explicit *outcome uncertain* error and reconnects for later calls). Read-only methods stay replayable; HTTP keeps its session-expired retry. |
| `compression.threshold_tokens` became a **default-on 256000 cap** upstream; apollo had no absolute cap, so it compressed far later on large-window models. | `Compression.triggerAt` fires at the lower of the ratio threshold and the cap (cap clamped to the window); `threshold_tokens: null` restores ratio-only. |
| Writes to files that steer the agent itself (`AGENTS.md`, `CLAUDE.md`, `SOUL.md`, `.cursorrules`) were ungated. | `approvals.protected_instruction_files` (default on) + `protected_instruction_extra_patterns`: `write_file`/`patch` ask a human **every time, even under `--yolo`**, and an unattended surface refuses. |
| MCP keepalive pinged **stdio** children every 180s; upstream now defaults keepalive to HTTP servers only. | Default is 0 (off) for stdio, 180s for HTTP; an explicit `keepalive_interval` opts a stdio server back in (still floored at 5s). |
| The background auto-reviewer held the full `memory` tool, including `remove` (upstream #106310). | `ToolContext.memoryDeletesAllowed` is false for the reviewer: it may add or amend, never delete. |
| `cronjob_manage` was missing upstream's **`trigger`** action. | `trigger` queues an off-tick run (`run_requested_at`) that the scheduler honours **without** advancing `next_run_at`, so running now never cancels the scheduled run (upstream #106306). A paused job can still be triggered. |
| `display.show_reasoning` default was `false`; upstream flipped it to `true`. | Default is now `true`. |
| The webhook-safe toolset lacked `vision_analyze`, and the `skills` toolset lacked `skill_manage`. | Both match upstream's sets. |
| `/reasoning` split awkwardly across two apollo commands, and `/history` was an alias of `/status`. | `/reasoning <level\|show\|hide>` is one command (`/reasoning-display` kept as an alias); `/history` now prints the conversation, as upstream's does. |
| The native Anthropic model list predated the Claude 5 family. | `claude-opus-5`, `claude-fable-5-1`, `claude-sonnet-5`, `claude-haiku-4-5` (4.6 ids kept). |

**Checked and already aligned** (no change needed): MCP OAuth refresh keeps the
old refresh token when a server omits one (upstream #106185's class); Anthropic
requests send exactly one credential header (#107978); `tool_search` matches the
whole query, so it never returns the "five tools sharing one word" noise (#106676);
cron advances schedules before executing (at-most-once); upstream **deleted** the
`session_reset` section entirely, which apollo never implemented.

**New upstream config accepted-and-ignored** (the contract holds; each is a
roadmap candidate, not a bug): `agent.auto_recovery_cycles` (a wait-and-retry
layer after the fallback chain is spent), `agent.restart_after_turn_timeout`,
`agent.verify_on_stop`, `delegation.fallback_providers`, `auxiliary.vision.*`,
`auxiliary.compression.no_progress_timeout`, `title_generation.model_upgrade_enabled`,
`cron.catch_up_missed`, provider `extra_body` / `session_affinity_header`,
`model.ollama_num_ctx`, OpenRouter per-model routing overrides, browser
`restrict_evaluate` / `allow_unsafe_evaluate`, the dict form of `reasoning_effort`,
Telegram `drop_pending_on_cold_boot` / `allow_cjk_rich_messages`, Discord
`bots_require_inline_mention` / `free_response_auto_thread`, `tts.delivery_profiles`,
`transcribe.openai.timeout`/`max_retries`, `display.suppress_warning_notifications`,
the new top-level `auth:` section, and `updates.check`. Upstream also **removed**
`model.max_tokens` as user configuration ("output limits are provider-owned");
apollo still honours it, a harmless superset.

**Open, deliberately not built in this pass** (features, not drift): MCP `lazy:`
servers (register from an on-disk schema cache, connect on first tool call — needs
a cache format apollo has no equivalent of), and the two new upstream provider
plugins `alibaba-coding-plan` and `copilot-acp`. Upstream's REPL registry is now
102 commands (was 92); the newly feasible ones for apollo are `/undo`, `/export`,
`/context`, `/toolsets` and `/reload-mcp`.

## Method / reproducing this audit

Two parallel agents: one censused apollo's code (`shared|jvm|native/src/main`),
one mapped Hermes from the upstream repo. Re-run when Hermes moves — it is
date-tag versioned and changes fast.

The 2026-09-21 re-audit was narrower and mechanical, and is the cheaper one to
repeat:

1. `gh api repos/NousResearch/hermes-agent/commits/main` for the new baseline,
   and the release notes for every tag since the last audited commit — they name
   the behavioural fixes by PR number.
2. Diff `cli-config.yaml.example` against
   `shared/src/test/resources/cli-config.yaml.example`; every added key is either
   a new knob to implement or a parse-and-ignore to record, and every changed
   default is possible drift. Refresh the fixture and run
   `sbt "agentJVM/testOnly *UpstreamConfigCompatSuite"` — that test **is** the
   config contract.
3. Diff the registries: `toolsets.py` against `apollo/tools/Toolsets.scala`,
   `hermes_cli/commands.py`'s `COMMAND_REGISTRY` against
   `ReplCommands.commandCatalog`, and `plugins/model-providers/*/` against
   `Profiles.scala`.
4. For each behavioural fix named in the notes, `gh api
   repos/NousResearch/hermes-agent/pulls/<n>` — the PR body states the intended
   contract precisely enough to mirror without reading the Python.

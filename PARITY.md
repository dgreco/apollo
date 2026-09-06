# apollo vs Hermes — Feature Parity

A sourced map of where apollo stands against the upstream **NousResearch Hermes
agent** (`github.com/NousResearch/hermes-agent`, audited at commit `089bb32`,
release `v2026.8.31`). Companion to `REPL_PARITY.md` (which covers the REPL
slash commands specifically).

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
  polls the Messages `chat.db` via `sqlite3` and sends via `osascript`. Email is
  the remaining one — see below (now done via kyo-net TLS sockets).

## Group C — out of apollo's scope (platform, not core agent)

Plugins ecosystem · Electron desktop app + web dashboard + TUI · ACP editor
integration (VS Code/Zed/JetBrains) · LSP · computer-use · voice/wake/TTS/
transcription · billing/credits/pricing · egress proxy / credential firewall ·
kanban / hosted rooms / multi-agent collab · learning-journey / pets /
achievements · suggestion / blueprint engine · observability (OTLP) · secrets
managers (1Password/Bitwarden) · checkpoints / git-safety · context engine ·
enterprise connectors (Feishu, MS Graph, Spotify, Home Assistant, X search).

These would each require building a whole subsystem; they are deliberately **not**
pursued unless apollo's remit changes from "core agent" to "platform".

---

## Group A & B status

**All Group-A items and their follow-ups are built** (each CI-green, JVM + Native):
`vision_analyze`, Bedrock ConverseStream streaming, `execute_code`, `tool_search`,
MCP sampling/elicitation (**stdio + streamable-HTTP**), provider OAuth (**Copilot +
Qwen**, plus Vertex/Azure via `model.key_cmd`), the **guards** (repetition /
empty-response / prompt-cache), **background auto-review**, and **more terminal
backends** (singularity + generic `exec`).

**Group B (breadth) is built too**: `image_generate`, Skills Hub, **browser
automation** (CDP over WebSocket), and gateway platforms **Matrix, WhatsApp Cloud,
and Twilio SMS**.

Still open (deliberately, or larger): MCP **server mode** + legacy HTTP+SSE
transport; OAuth for **ChatGPT/Codex** (loopback-PKCE broker), **xAI**, native
Gemini, MoA; email (IMAP/SMTP) / Teams / iMessage gateways; video generation; and
live validation of the reverse-engineered Copilot/Qwen and Bedrock-stream flows.
Group C stays out of scope (platform, not core agent).

## Method / reproducing this audit

Two parallel agents: one censused apollo's code (`shared|jvm|native/src/main`),
one mapped Hermes from the upstream repo. Re-run when Hermes moves — it is
date-tag versioned and changes fast.

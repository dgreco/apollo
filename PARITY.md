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
| **Background auto-review** | Hermes forks an agent after each turn to *actually save* skills/memory; apollo's nudges only *remind*. |
| ~~**MCP: sampling + elicitation**~~ | **Done (stdio)** — server-initiated `sampling/createMessage` (runs a model call) and `elicitation/create` (asks the user / declines) handled by `McpClient`. Still open: same for the **HTTP** transport, **MCP server mode**, legacy **SSE**. |
| ~~**Provider OAuth**~~ | **Copilot done** — `apollo auth copilot login` (OAuth device-code) + GitHub→Copilot token exchange; `copilot` is now a resolvable provider. ⚠️ Reverse-engineered constants; the live flow needs hands-on validation. Still unsupported: ChatGPT/Codex, xAI, Qwen, Vertex, Azure Entra, native Gemini, MoA. |
| ~~**Bedrock streaming**~~ | **Done** — `ConverseStream` decoded via `apollo.util.EventStream`; selected by `model.streaming`. |
| ~~**Guards**~~ | **Done** — repetition guard (breaks a turn on `agent.repetition_limit` identical tool calls), empty-response guard (re-prompts `agent.empty_response_retries` times, then exits `empty_response`), and Anthropic prompt-cache breakpoints on system+tools+rolling-last-message (`prompt_cache.enabled`). |
| **More terminal backends** | Hermes has 7 (local/docker/ssh + singularity/modal/daytona/vercel-sandbox); apollo has 3. |

## Group B — breadth features (bigger; arguably in-scope)

- Vision analysis (input) done in Group A. **`image_generate`** done — a tool
  hitting an OpenAI-compatible `/images/generations` endpoint (`image.*` config),
  saving the PNG. Video generation still open (niche/heavy).
- **Browser automation** (multiple backends in Hermes).
- ~~**Skills Hub**~~ **done (install/search)** — `apollo skills install <git-url|owner/repo|dir>`
  (git clone / local copy) and `apollo skills search <q>` over a catalog
  (`skills.hub_catalog_url`). Ledger/linter/curate still open.
- **More gateway platforms** — **Matrix done** (HTTP `/sync` long-poll + send,
  MATRIX_HOMESERVER/MATRIX_ACCESS_TOKEN), alongside Telegram/Discord/Slack. Email
  (IMAP/SMTP), SMS, WhatsApp, Teams, iMessage remain (non-HTTP or vendor-SDK; out
  of the current HTTP-only fit, except WhatsApp Cloud/SMS which could follow).

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

## Group A status

**All Group-A items are now built** (each CI-green, JVM + Native): `vision_analyze`,
Bedrock ConverseStream streaming, `execute_code`, `tool_search`, MCP
sampling/elicitation (stdio), and Copilot OAuth. Remaining follow-ups noted inline
above: MCP HTTP server-requests + server mode + legacy SSE; the other OAuth
providers; and Bedrock/Copilot live validation. Group B and Group C are unchanged
(breadth / out-of-scope).

## Method / reproducing this audit

Two parallel agents: one censused apollo's code (`shared|jvm|native/src/main`),
one mapped Hermes from the upstream repo. Re-run when Hermes moves — it is
date-tag versioned and changes fast.

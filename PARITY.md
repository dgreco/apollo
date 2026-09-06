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
| **Provider OAuth** | Copilot, ChatGPT/Codex, xAI, Qwen, Vertex, Azure Entra, native Gemini `generateContent`, and the **MoA** virtual provider — apollo currently marks these unsupported. |
| ~~**Bedrock streaming**~~ | **Done** — `ConverseStream` decoded via `apollo.util.EventStream`; selected by `model.streaming`. |
| **Guards** | repetition guard, empty-response guard, prompt-cache boundary management. |
| **More terminal backends** | Hermes has 7 (local/docker/ssh + singularity/modal/daytona/vercel-sandbox); apollo has 3. |

## Group B — breadth features (bigger; arguably in-scope)

- Vision analysis (input) is cheap given the wire support; **image/video
  generation** is a new provider surface.
- **Browser automation** (multiple backends in Hermes).
- **Skills Hub** — remote skill install/search/curate, ledger, linter.
- **More gateway platforms** — email, SMS, Matrix, WhatsApp, Teams, iMessage, …
  (Hermes has ~25).

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

## Recommended order

`vision_analyze` → MCP sampling/elicitation + server mode → `tool_search` →
`execute_code` → Bedrock streaming → provider OAuth (Copilot + native Gemini
first). Everything in Group C stays out of scope by default.

## Method / reproducing this audit

Two parallel agents: one censused apollo's code (`shared|jvm|native/src/main`),
one mapped Hermes from the upstream repo. Re-run when Hermes moves — it is
date-tag versioned and changes fast.

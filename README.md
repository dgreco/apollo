# apollo ☀

**apollo** is a from-scratch clone of the [Hermes agent harness](https://github.com/NousResearch/hermes-agent)
(Nous Research), written in **Scala 3** on the **[Kyo](https://getkyo.io)** effect
system and **cross-built for the JVM and Scala Native** (a single self-contained
native binary, no runtime dependencies beyond a TLS library).

It is a *terminal-first AI agent*: you give it a task, it plans, calls tools
(files, shell, web, MCP servers, subagents), and works until the task is done —
in an interactive REPL, as a one-shot command, or as a long-running **gateway**
that answers on Telegram, Discord, Slack, Matrix, WhatsApp, SMS, Teams, and iMessage. It talks to any of ~40 model
providers over four wire protocols, and it reads its configuration the *same way*
Python Hermes does, from its own home directory (`~/.apollo`), so a Hermes
`config.yaml` / `.env` copied in works unchanged.

> New to the internals? [`ARCH.md`](./ARCH.md) documents the architecture (C4
> model) and walks through, step by step, exactly what happens when a prompt is
> received.

---

## Highlights

- **Providers** — every slug in the Hermes registry (`openrouter`, `anthropic`,
  `openai-api`, `gemini`, `deepseek`, `xai`, `groq`, `mistral`, `bedrock`, …,
  plus `custom`/`ollama`/`vllm`). Four wire protocols: OpenAI chat-completions,
  Anthropic messages, OpenAI Responses, and AWS Bedrock Converse (SigV4-signed).
  Auto-detection, `${VAR}` expansion, per-model reasoning effort, and
  `fallback_providers` failover.
- **Tools** — `read_file` / `write_file` / `patch` / `search_files`, `terminal`
  + `process_manage` (local, `docker exec`, `ssh`, `singularity`, or a generic
  sandbox-CLI backend), `todo_list`, `memory`, `skills_*`, `clarify`,
  `session_search`, `delegate_task` (real subagents), `cronjob_manage`,
  `web_search` / `web_extract`, `vision_analyze`, `image_generate`,
  `video_generate`, `execute_code`, `tool_search`, `browser` (headless Chrome
  over CDP), `text_to_speech` / `transcribe`, plus every tool exported by
  connected **MCP servers**.
- **MCP client** — stdio and streamable-HTTP transports, OAuth 2.1 + PKCE, a
  reliability ladder (circuit breaker / keepalive / reconnect / parking), and
  `apollo mcp add|remove|list|test|login|reauth|logout`.
- **Gateway** — one process hosting Telegram, Discord, Slack, Matrix, WhatsApp, SMS, Teams, iMessage, an
  OpenAI-compatible HTTP API, a generic webhook, and a cron scheduler, with
  optional live-streaming replies.
- **Agent core** — role-alternation repair, persist-before-execute durability,
  retries with fallback, two-phase context compression, self-improvement
  nudges, and interruptible turns.
- **Observability** — content-free logs, traces, and metrics for the prompt →
  response lifecycle (`kyo.Log` + `kyo-stats`), exported as OTLP/HTTP; `/trace`
  and `/metrics` in the REPL, and a one-command Jaeger + Prometheus + Grafana
  demo stack in `observability/`.
- **Config-compatible with Hermes** — same files, formats, precedence, home
  layout, and skill format (agentskills.io); verified against a live Hermes
  install and the real 2,148-line `cli-config.yaml.example`.

---

## Requirements

| To… | You need |
|---|---|
| Build / run on the **JVM** | **JDK 25 or newer** (kyo RC6's compile-time macros are built for Java 25; JDK 21 fails with `UnsupportedClassVersionError`) and [sbt](https://www.scala-sbt.org/) 2.0.8 (`project/build.properties`; the repo pins Scala 3.9.0) |
| Build the **native** binary | the above, plus **OpenSSL 3.x** headers + libs at build *and* run time (Homebrew `openssl@3`, or the distro `-dev` package), and a C toolchain (Clang/LLVM) for Scala Native |
| **Docker** terminal backend | a reachable Docker daemon + a running container |
| **SSH** terminal backend | the `ssh` client on `PATH` and key-based access to the host |
| **Web search** | a `BRAVE_SEARCH_API_KEY` (the tool auto-hides without it) |

The build's native linker step is memory-hungry; `.jvmopts` already sets
`-Xmx8G`.

---

## Building

```bash
# ---- JVM ----
sbt agentJVM/run                       # compile + run from sbt
sbt agentJVM/assembly                  # build a fat jar
java -jar target/out/jvm/scala-3.9.0/apollo/apollo.jar

# ---- Native (single self-contained binary) ----
sbt agentNative/nativeLink             # ~1–2 min; links a native executable
./target/out/native0.5/scala-3.9.0/apollo/apollo
```

Native notes:

- OpenSSL 3.x must be linkable at build time and present at run time (kyo-http's
  TLS shim). The build probes Homebrew / MacPorts / pkgsrc / system locations;
  if it can't find OpenSSL, TLS-dependent features won't link.
- Classpath resources are embedded into the binary
  (`nativeConfig.withEmbedResources(true)`), so bundled fixtures resolve at
  runtime the same as on the JVM.

The two builds are byte-for-byte behaviorally identical except where a platform
genuinely differs (line editor, session-search backend, Ctrl-C handling, and the
email gateway — which needs JDK TLS sockets, so it is JVM-only) — those gaps are
called out in [`ARCH.md`](./ARCH.md).

---

## Running

### First run

With no credentials configured, any interactive launch drops into the **setup
wizard**, which writes your provider/model into `~/.apollo/config.yaml` and your
API key into `~/.apollo/.env`:

```bash
apollo            # (or: sbt agentJVM/run) → setup wizard on first run
apollo setup      # re-run the wizard any time
```

### Interactive REPL

```bash
apollo                       # start chatting
```

Startup shows a **welcome screen** (ASCII-art title + a framed panel of available
tools and skills grouped by category, with your model, cwd, and session), and
each turn ends with a **status line** (model · context usage · tokens · turn time).

Type `/` to see the commands: the native REPL shows a **live menu** that filters
as you type; on the JVM (JLine) press **TAB** to list/complete. **TAB** completes
the current command on both (`APOLLO_NO_COMMAND_MENU=1` disables the native menu).

REPL slash commands (type `/help` in-session for the full list):

| | |
|---|---|
| **Session** | `/status` · `/history` (model, message count, token usage, context %), `/usage`, `/config`, `/profile`, `/reset` · `/new`, `/clear`, `/redraw`, `/title <name>`, `/compress` · `/compact` (force compaction), `/save [file.md]`, `/prompt` · `/compose` (multi-line), `/retry`, `/copy`, `/image <path>` (attach to next message), `/sessions`, `/resume <id\|latest>`, `/branch` · `/fork [name]` |
| **Model** | `/model [name]`, `/reasoning <level>`, `/reasoning-display`, `/verbose`, `/version` · `/v`, `/whoami` |
| **Work** | `/plan <task>`, `/init [notes]` (write AGENTS.md), `/diff [args]`, `/loop <prompt> [--times N] [--every S]`, `/bg <prompt>` (background session), `/agents` · `/tasks`, `/stop [id]`, `/review [focus]` (independent subagent review), `/goal [text\|show\|clear]` (standing objective), `/queue [prompt\|clear]`, `/moa <prompt>` (mixture-of-agents), `/learn <what>` (capture a skill), `/heartbeat` · `/hb [every <interval> <prompt>\|status\|pause\|resume\|clear]` (recurring idle prompt), `/steer <message>` (inject after the next tool call), `/worktree [list\|new [name]\|prune]`, `/snapshot [create\|list\|restore <id>\|prune]`, `/rollback [list\|create\|<number>]` (git working-tree checkpoints), `/blueprint` · `/bp [name [k=v…]]` (cron job from a template), `/kanban [show\|add\|move\|rm]` (local board), `/curator [status\|archive\|restore]` (skill maintenance), `/handoff <telegram\|discord\|slack>` (continue this session via a running gateway bot) |
| **Tools & services** | `/tools`, `/skills`, `/reload-skills`, `/mcp` (server status + tools), `/cron` (scheduled jobs), `/memory` |
| **Approvals** | `/yolo` (toggle bypass), `/approvals [manual\|off]` |
| **Exit** | `/quit` · `/exit` · `/q` |

**Concurrent-input mode (experimental, opt-in).** Set `display.async_input: true`
in `config.yaml` to run each turn on a background fiber while the prompt stays
live — so you can type **`/steer <message>`** to guide the model mid-turn,
**`/stop`** to cancel, or **`/queue`**/plain messages to line up follow-ups,
all *while the turn is still streaming*. Turn output is printed above the input
line via `LineEditor.printAbove` (JLine on the JVM; a hand-rolled termios redraw
on Scala Native). Its terminal behavior is **best-effort/experimental** —
validate it on your terminal before relying on it. Default (off) keeps the
standard synchronous REPL.

### One-shot and seeded runs

```bash
apollo -z "summarize README.md"        # one-shot: print the final answer and exit
                                       #   (approval-bypassed; final text only)
apollo -q "start here" -Q              # seed one turn, answer, and exit (quiet)
apollo -q "start here"                 # seed the first turn, then stay in the REPL
```

### Common flags

| Flag | Meaning |
|---|---|
| `-m, --model <id>` | model override for this run |
| `--provider <name>` | provider override (e.g. `anthropic`, `openrouter`, `bedrock`) |
| `--reasoning <level>` | `none`/`minimal`/`low`/`medium`/`high`/`xhigh`/`max` |
| `-t, --toolsets <a,b>` | comma-separated toolsets for this run (also gates MCP servers) |
| `-r, --resume <id\|title\|latest>` | resume a stored session |
| `-c, --continue [name]` | resume the most recent session (or one matching `name`) |
| `-p, --profile <name>` | use the `$APOLLO_HOME/profiles/<name>` home |
| `--yolo` | bypass command-approval prompts (hardline/deny globs still apply) |
| `--ignore-user-config` | ignore `config.yaml`, run on built-in defaults |
| `-V, --version` / `-h, --help` | version / usage |

### Subcommands

```
apollo model          show the resolved provider / model / base URL / api mode
apollo config         show | get <key> | path | env-path
apollo sessions       list stored sessions
apollo skills         list installed skills
apollo tools          list toolsets and their tools
apollo memory         print MEMORY.md and USER.md
apollo status         config + provider health check (alias: doctor)
apollo cron           list scheduled jobs (run-scheduler starts the tick loop)
apollo mcp            list | test | add | remove | login | reauth | logout
apollo gateway        run the messaging gateway
apollo setup          interactive provider setup wizard
```

---

## Managing

Everything lives under the **home directory** — `$APOLLO_HOME`, or `~/.apollo`
(`%LOCALAPPDATA%\apollo` on Windows). `HERMES_HOME` is deliberately ignored so a
shell configured for Python Hermes never steers apollo. Behavior-toggle env vars
use `APOLLO_*` names, with the `HERMES_*` spellings still accepted.

```
~/.apollo/
  config.yaml            # Hermes-format configuration
  .env                   # secrets (KEY=VALUE); overrides process env
  .op.env                # optional gap-fill secrets (1Password etc.)
  memories/MEMORY.md     # environment facts the agent has learned
  memories/USER.md       # user preferences / profile
  skills/<name>/SKILL.md # agentskills.io-format skills
  cron/jobs.json         # scheduled jobs
  mcp-tokens/            # cached MCP OAuth tokens (0600)
  logs/mcp-stderr.log    # MCP server stderr
  scala-state/           # apollo-owned: session transcripts + index, search db
  profiles/<name>/       # per-profile homes (same layout)
  active_profile         # sticky default profile
```

### Configuration

`config.yaml` is read exactly the way Hermes reads it. Layering, lowest to
highest precedence:

1. shell environment
2. `~/.apollo/.env` (overrides the shell — a rotated key beats a stale export)
3. `~/.apollo/.op.env` (gap-fill only)
4. managed scope: `$APOLLO_MANAGED_DIR` or `/etc/apollo` — its `config.yaml`
   deep-merges *over* the user config and its `.env` wins outright (for
   IT-pushed defaults)

Inspect the resolved config:

```bash
apollo config path                 # where config.yaml lives
apollo config show                 # print it
apollo config get model.default    # one key, with ${VAR} expanded
```

Safety rails: `--ignore-user-config` (or `APOLLO_IGNORE_USER_CONFIG=1`) runs on
built-in defaults; a one-shot `-z` run **refuses to start** against an
unparseable `config.yaml` so defaults can't silently spend `.env` credentials.

### Profiles

Keep isolated homes (separate creds, sessions, memory) side by side:

```bash
apollo -p work "..."       # uses ~/.apollo/profiles/work
```

`-p` wins; otherwise a profile-shaped `APOLLO_HOME` is trusted as-is, else the
sticky `active_profile` file supplies the default. Names must match
`^[a-z0-9][a-z0-9_-]{0,63}$`; `default` maps to the root.

### Providers & credentials

Set a provider/model in `config.yaml` (`model.provider`, `model.default`) or via
`APOLLO_INFERENCE_PROVIDER` / `_MODEL`, and the API key in `.env`. With
`provider: auto`, apollo detects a provider from whichever key env var is
present. Named custom providers (`providers:` map, with `key_env` / `key_cmd` /
`api_mode` / `extra_headers`) and `model_aliases:` work as in Hermes.

```bash
apollo model               # show the fully-resolved runtime
apollo status              # health check: config present? provider resolvable?
apollo -m claude-opus-4.6 --provider anthropic --reasoning high "..."
```

`fallback_providers: [openrouter, anthropic]` fails a turn over to the next
provider when the primary errors after its own retries.

Bedrock: set `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY`
(+ `AWS_SESSION_TOKEN`, `AWS_REGION`) and select `--provider bedrock`; requests
are SigV4-signed with no AWS SDK.

### Sessions

Every run is recorded as a JSONL transcript under `scala-state/`. Resume by id,
title, or `latest`; search past conversations with the `session_search` tool or:

```bash
apollo sessions            # list recent sessions
apollo -c                  # continue the most recent
apollo -r latest           # same, explicitly
```

### Memory & skills

The agent maintains `memories/MEMORY.md` (environment facts) and
`memories/USER.md` (your preferences) via the `memory` tool, and is nudged to
persist knowledge and capture reusable **skills** on a cadence
(`memory.nudge_interval`, `skills.creation_nudge_interval`). Skills use the
agentskills.io layout under `skills/`; extra read-only dirs come from
`skills.external_dirs`.

```bash
apollo skills              # list installed skills
apollo memory              # print MEMORY.md + USER.md
```

### Tools & approvals

Toolsets are named bundles (`apollo-cli`, `debugging`, `safe`, `file`, `web`, …)
selected per platform (`platform_toolsets`) or per run (`-t`), with
`agent.disabled_toolsets` subtracted last.

Dangerous shell commands are gated by an approval flow: hardline-destructive
patterns and `approvals.deny` globs are blocked even under `--yolo`; otherwise
you get an interactive once/session/always/deny prompt (bounded by
`approvals.timeout`). Unattended contexts (cron, webhook, `-q` quiet, `-z`
one-shot) follow their configured `approvals.*_mode`.

The `terminal` tool runs on the backend chosen by `terminal.backend`:

```yaml
terminal:
  backend: docker          # or: local (default) | ssh
  docker: { container: mybox, workdir: /app }
  ssh:    { host: box.example.com, user: deploy, key_path: ~/.ssh/id }
```

### MCP servers

Declare servers in the top-level `mcp_servers:` block, or manage them from the CLI:

```bash
apollo mcp add time --command uvx --arg mcp-server-time
apollo mcp add notion --url https://mcp.notion.com/mcp --auth oauth
apollo mcp list
apollo mcp test time                 # connect + list tools
apollo mcp login notion              # OAuth 2.1 + PKCE (browser or paste)
apollo mcp reauth notion             # clear + re-auth
apollo mcp remove time
```

Stdio servers spawn a subprocess; HTTP servers use streamable HTTP; both expose
their tools to the model as `mcp__<server>__<tool>`. See [`ARCH.md`](./ARCH.md)
for the reliability ladder and OAuth flow.

### Gateway

Run one process that answers on chat platforms, serves an API, and ticks cron:

```bash
export TELEGRAM_BOT_TOKEN=...                 # Telegram
export DISCORD_BOT_TOKEN=...                  # Discord
export SLACK_BOT_TOKEN=... SLACK_APP_TOKEN=...  # Slack (Socket Mode)
export API_SERVER_KEY=...                      # OpenAI-compatible API (port 8642)
export WEBHOOK_SECRET=...                      # generic webhook (port 8644)
apollo gateway run
```

Each platform **denies by default**: authorize users with
`<PLATFORM>_ALLOWED_USERS` (or `GATEWAY_ALLOWED_USERS`), or open it with
`<PLATFORM>_ALLOW_ALL_USERS=true`. In group channels the bot answers only when
@mentioned (configurable). Set `streaming.enabled: true` to edit replies live as
tokens arrive. Cron jobs deliver to stdout or `telegram:` / `discord:` /
`slack:<id>` targets.

### Observability

Logs, traces, and metrics for the prompt → response lifecycle — built on
`kyo.Log` + `kyo-stats-registry`, exported in OTLP/HTTP JSON. Everything is
**content-free**: operation names, enums, token counts, and durations only,
never prompts, tool arguments, or results.

**No setup** — inspect the last turn right in the REPL:

```
/trace     # span tree: agent.turn → llm.call → tool.* (provider, model, tokens, TTFT, durations)
/metrics   # counters (turns, api_calls, tool_calls, errors, tokens) + latency histograms
```

**Live in your terminal** — env vars, no backend needed:

```bash
APOLLO_TRACE=1 apollo         # print the span tree after every response
APOLLO_LOG_LEVEL=debug apollo # stream turn events: →/← llm.call, →/← tool.*, retries, compress …
```

**The demo stack** — traces in Jaeger, metrics in Prometheus, dashboards in
Grafana, one command:

```bash
cd observability
docker compose up -d          # otel-collector + jaeger + prometheus + grafana
```

Enable export in `~/.apollo/config.yaml`, then use apollo and watch it flow:

```yaml
monitoring:
  export:
    otlp:
      enabled: true                     # traces  → /v1/traces
      metrics: true                     # metrics → /v1/metrics
      logs: true                        # logs    → /v1/logs
      endpoint: http://localhost:4318
```

Open **Grafana at <http://localhost:3000>** (no login) for the pre-built
_apollo · agent_ dashboard; Jaeger (<http://localhost:16686>) has the traces and
Prometheus (<http://localhost:9090>) the raw metrics. `apollo monitoring status`
shows what's active. Full walkthrough — including a no-Docker path — in
[`observability/README.md`](observability/README.md). Stop with
`docker compose down`.

---

## Testing

```bash
sbt "agentJVM/testOnly *"              # run the whole JVM suite (see note)
sbt "agentJVM/testOnly apollo.mcp.*"   # one package
sbt "agentNative/testOnly *"           # the suite on the native binary
```

> **Note:** bare `agentJVM/test` maps to `testQuick`, which skips suites
> unchanged since the last run and prints `No tests to run`. Use
> `testOnly *` to force a full run, or `sbt clean agentJVM/test`. Tests run
> serially (`Test / parallelExecution := false`) because the MCP manager and
> tool registry are process-global.

---

## Project layout

```
shared/src/main/scala/apollo/     # cross-platform sources
  Main.scala        cli/          # entry point; CLI, REPL, setup wizard, editor
  agent/            provider/     # conversation loop; provider resolution + wire adapters
  tools/            mcp/          # built-in tools + approvals; MCP client subsystem
  gateway/          session/      # Telegram/Discord/Slack/API/webhook + hub; transcript store
  config/           skills/       # home/config/env resolution; skill discovery
  core/  obs/  cron/  util/        # domain model; observability (logs/traces/metrics → OTLP); scheduler; helpers
jvm/src/main/    native/src/main/ # platform-specific: line editor, session-search backend
shared/src/test/ jvm/src/test/    # munit suites
observability/                    # docker-compose OTel demo stack (collector, Jaeger, Prometheus, Grafana)
```

See [`ARCH.md`](./ARCH.md) for how these fit together and how a turn flows
through them.

---

## Compatibility & scope

apollo is a *faithful subset* of Hermes: same config/credentials/home layout,
same core tools and wire schemas, same skill format and CLI shape. A full Hermes
`config.yaml` loads unchanged — keys for subsystems apollo doesn't implement are
parsed and ignored.

**Deliberately out of scope:** the Ink/Node TUI and desktop/web apps; the other
~23 gateway platforms and voice/attachments/Block-Kit; provider OAuth token
brokers (`openai-codex`/`copilot`/`vertex`); the MCP schema cache, `lazy`
servers, session recycling, and sampling; Modal/Daytona/Singularity terminal
backends; browser/computer-use and image/video tools; Honcho, plugins/hooks,
trajectory tooling, and telemetry.

Status: **0.1.0-SNAPSHOT** — an actively developed clone, not an official Nous
Research product.

<!--
SPDX-FileCopyrightText: 2026 David Greco <greco@acm.org>

SPDX-License-Identifier: Apache-2.0
-->

# Local observability stack

A one-command OpenTelemetry backend to watch apollo's traces, metrics, and logs
flow while you use the agent.

```
apollo ──OTLP/HTTP :4318──▶ otel-collector ──┬─▶ jaeger      traces  → http://localhost:16686
                                              ├─▶ prometheus  metrics → http://localhost:9090
                                              └─▶ stdout       logs    → docker compose logs -f otel-collector

                              grafana  →  http://localhost:3000   (dashboards over jaeger + prometheus)
```

**Start at Grafana (<http://localhost:3000>)** — it opens with no login and a
pre-built **apollo · agent** dashboard; Jaeger and Prometheus are wired in as
data sources. The direct Jaeger/Prometheus UIs are there when you want the raw
view.

Everything apollo exports is **content-free**: operation names, enums, token
counts, and durations only — never prompts, tool arguments, or results.

## 1. Start the backend

```bash
cd observability
docker compose up -d
```

- **Grafana** — <http://localhost:3000> (dashboard: apollo · agent)
- Jaeger UI — <http://localhost:16686>
- Prometheus UI — <http://localhost:9090>
- Collector logs (apollo's log pillar) — `docker compose logs -f otel-collector`

## 2. Point apollo at it

Add to `~/.apollo/config.yaml` (or `$APOLLO_HOME/config.yaml`):

```yaml
monitoring:
  export:
    otlp:
      enabled: true                     # traces  → /v1/traces
      metrics: true                     # metrics → /v1/metrics
      logs: true                        # logs    → /v1/logs
      endpoint: http://localhost:4318
      service_name: apollo
```

The endpoint can also come from the standard env var:

```bash
OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4318 apollo
```

but the per-pillar `enabled` / `metrics` / `logs` flags are what actually turn
export on. Check with `apollo monitoring status`.

## 3. Send a prompt, then look

Run apollo and have one exchange (send a message, let it call a tool or two).

**Traces (Jaeger).** Open <http://localhost:16686>, pick service **apollo**, Find
Traces. Each turn is one trace:

```
agent.turn                     ← exit_reason, api_calls, tokens, duration
├─ llm.call                    ← provider, model, tokens.in/out, ttft_ms
├─ tool.read_file              ← tool.name, duration_ms, error
├─ llm.call
└─ tool.bash
```

**Metrics (Grafana / Prometheus).** The **apollo · agent** dashboard in Grafana
shows it all: turns / api-calls / tool-calls / errors counters, throughput and
tokens/s, and average LLM / tool / TTFT / turn latency. To query raw in
Prometheus (<http://localhost:9090>), apollo's dotted names map to underscores —
the collector is set to `add_metric_suffixes: false`, so they stay clean:

| apollo metric        | Prometheus series                                    |
|----------------------|------------------------------------------------------|
| `apollo.turns`       | `apollo_turns`                                        |
| `apollo.api_calls`   | `apollo_api_calls`                                    |
| `apollo.tool_calls`  | `apollo_tool_calls`                                   |
| `apollo.errors`      | `apollo_errors`                                       |
| `apollo.tokens_input`/`_output` | `apollo_tokens_input` / `apollo_tokens_output` |
| `apollo.turn_ms`     | `apollo_turn_ms_count` / `_sum` / `_bucket`          |
| `apollo.llm_ms`, `apollo.tool_ms`, `apollo.ttft_ms` | same `_count`/`_sum`/`_bucket` shape |

Try: `rate(apollo_api_calls[1m])`, or
`rate(apollo_llm_ms_sum[5m]) / rate(apollo_llm_ms_count[5m])` for mean LLM
latency (the histograms are exported as a single `+Inf` bucket, so use
sum/count for averages rather than `histogram_quantile`).

**Logs.** `docker compose logs -f otel-collector` shows the structured turn
events (turn start, `→/← llm.call`, `→/← tool.*`, retries, fallovers, compress,
turn end), each stamped with its trace/span id.

## No collector needed for a quick look

You don't need any of this just to see a turn: in the REPL, `/trace` prints the
last turn's span tree and `/metrics` prints the counters + latency histograms —
both work with zero configuration. `APOLLO_TRACE=1` prints the span tree after
every response; `APOLLO_LOG_LEVEL=debug` streams the operation events (`→/←
llm.call`, `→/← tool.*`, compress, retries) to your terminal, and
`APOLLO_LOG_LEVEL=trace` adds the fine-grained per-turn detail on top —
iteration cadence, first-token/TTFT, and tool-round entries.

## Stop / reset

```bash
docker compose down          # stop
docker compose down -v       # stop and drop volumes (fresh Jaeger/Prometheus)
```

## Grafana

Everything is provisioned from `grafana/`:

- `provisioning/datasources/datasources.yaml` — Prometheus (default) + Jaeger.
- `provisioning/dashboards/dashboards.yaml` — loads every dashboard under
  `grafana/dashboards/` into an **apollo** folder, live-reloading every 10s.
- `dashboards/apollo.json` — the **apollo · agent** dashboard.

Edit `apollo.json` (or build panels in the UI and export back to that file) and
Grafana picks it up without a restart. Anonymous admin is on for convenience —
remove `GF_AUTH_ANONYMOUS_*` from the compose file for anything you share.

## Notes

- Images are pinned in `docker-compose.yml`; bump them freely. If you move to
  Jaeger v2 (`jaegertracing/jaeger`), its OTLP intake and UI ports differ.
- Metric names are kept suffix-free via the collector's
  `prometheus.add_metric_suffixes: false`; drop that line if you prefer the
  conventional `_total` counter suffix (update the dashboard queries to match).

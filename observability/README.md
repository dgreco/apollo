# Local observability stack

A one-command OpenTelemetry backend to watch apollo's traces, metrics, and logs
flow while you use the agent.

```
apollo ──OTLP/HTTP :4318──▶ otel-collector ──┬─▶ jaeger      traces  → http://localhost:16686
                                              ├─▶ prometheus  metrics → http://localhost:9090
                                              └─▶ stdout       logs    → docker compose logs -f otel-collector
```

Everything apollo exports is **content-free**: operation names, enums, token
counts, and durations only — never prompts, tool arguments, or results.

## 1. Start the backend

```bash
cd observability
docker compose up -d
```

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

**Metrics (Prometheus).** Open <http://localhost:9090> and query. apollo's dotted
names are normalized by Prometheus convention:

| apollo metric        | Prometheus series                                     |
|----------------------|-------------------------------------------------------|
| `apollo.turns`       | `apollo_turns_total`                                  |
| `apollo.api_calls`   | `apollo_api_calls_total`                              |
| `apollo.tool_calls`  | `apollo_tool_calls_total`                             |
| `apollo.errors`      | `apollo_errors_total`                                 |
| `apollo.tokens_input`/`_output` | `apollo_tokens_input_total` / `apollo_tokens_output_total` |
| `apollo.turn_ms`     | `apollo_turn_ms_count` / `_sum` / `_bucket`           |
| `apollo.llm_ms`, `apollo.tool_ms`, `apollo.ttft_ms` | same `_count`/`_sum`/`_bucket` shape |

Try: `rate(apollo_api_calls_total[1m])`, or
`apollo_llm_ms_sum / apollo_llm_ms_count` for mean LLM latency.

**Logs.** `docker compose logs -f otel-collector` shows the structured turn
events (turn start, `→/← llm.call`, `→/← tool.*`, retries, fallovers, compress,
turn end), each stamped with its trace/span id.

## No collector needed for a quick look

You don't need any of this just to see a turn: in the REPL, `/trace` prints the
last turn's span tree and `/metrics` prints the counters + latency histograms —
both work with zero configuration. `APOLLO_TRACE=1` prints the span tree after
every response; `APOLLO_LOG_LEVEL=debug` streams the log events to your terminal.

## Stop / reset

```bash
docker compose down          # stop
docker compose down -v       # stop and drop volumes (fresh Jaeger/Prometheus)
```

## Notes

- Images are pinned in `docker-compose.yml`; bump them freely. If you move to
  Jaeger v2 (`jaegertracing/jaeger`), its OTLP intake and UI ports differ.
- To add dashboards, drop a Grafana service in and point it at the Prometheus
  and Jaeger data sources — the collector already emits everything it needs.

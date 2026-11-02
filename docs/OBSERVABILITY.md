# Observability

LedgerForge exports **metrics** for Prometheus and **traces** over OpenTelemetry (OTLP). Every log line carries a request id and, when tracing is on, a trace id.

## Metrics

`GET /actuator/prometheus` needs a separate, read-only **metrics token** (`LEDGERFORGE_METRICS_TOKEN`, at least 32 characters and different from the admin token). Tenant API keys and the admin token are refused, so the scraper holds the least privilege it needs.

Prometheus scrape config:

```yaml
scrape_configs:
  - job_name: ledgerforge
    metrics_path: /actuator/prometheus
    authorization:
      type: Bearer
      credentials_file: /etc/prometheus/ledgerforge-metrics-token
    static_configs:
      - targets: ["api:8080"]
```

### Business metrics

| Metric | Tags | Meaning |
|---|---|---|
| `ledgerforge_transfers_total` | `outcome` (posted, replayed, rejected, error), `reason` (error code) | Business outcome of transfer requests that reached the ledger (`error` = unexpected failure). Malformed requests rejected before that (invalid body, missing `Idempotency-Key`) show in `http_server_requests_seconds_count{uri="/v1/transfers",status="400"}` |
| `ledgerforge_fraud_decisions_total` | `decision` (ALLOW, REVIEW, BLOCK, unavailable, error) | Fraud-model decisions |
| `ledgerforge_assistant_questions_total` | `outcome` | AI assistant questions |
| `ledgerforge_assistant_tokens_total` | `direction` (input, output) | Claude tokens used (cost) |
| `ledgerforge_rate_limited_total` | `client` (api_key, ip) | Requests refused by the rate limiter |
| `ledgerforge_rate_limiter_errors_total` | | Redis unavailable: decisions taken without the shared store (alert on any) |

Tags are small fixed sets, never tenant or account ids, so the number of time series stays bounded.

Spring also exports HTTP latency (`http_server_requests_seconds`), outbound calls to the fraud service (`http_client_requests_seconds`), the database pool (`hikaricp_*`) and the JVM (`jvm_*`).

### Suggested alerts

| Alert | Expression (sketch) |
|---|---|
| Fraud service failing | `rate(ledgerforge_fraud_decisions_total{decision=~"unavailable|error"}[5m]) > 0` |
| Block rate jumps | BLOCK share of decisions doubles against the previous day |
| Assistant cost | `increase(ledgerforge_assistant_tokens_total[1h])` above budget |
| Abuse | `rate(ledgerforge_rate_limited_total[5m])` sustained |
| Rate limiter degraded | `increase(ledgerforge_rate_limiter_errors_total[5m]) > 0` |
| Latency | p99 of `http_server_requests_seconds{uri="/v1/transfers"}` above 500 ms |

## Traces

Tracing uses Micrometer Tracing with the OpenTelemetry bridge. Spans cover incoming HTTP requests and the outbound call to the fraud service, and the W3C `traceparent` header is sent with that call. The fraud service does not record spans yet, so a trace ends at the ledger's client span; database queries are not traced individually (HikariCP pool metrics cover the database).

Export is off until an endpoint is set:

```bash
MANAGEMENT_OTLP_TRACING_ENDPOINT=http://otel-collector:4318/v1/traces
TRACING_SAMPLING=0.1   # share of requests traced (default 10%)
```

Any OTLP backend works: Jaeger, Grafana Tempo, Honeycomb or the OpenTelemetry Collector.

## Health

`/actuator/health` (public) includes the database. The Redis health check is off by default (`REDIS_HEALTH_ENABLED`): because rate limiting fails open, a Redis outage should not make the load balancer remove healthy instances. Watch `ledgerforge_rate_limiter_errors_total` instead. Liveness and readiness probes are at `/actuator/health/liveness` and `/actuator/health/readiness`.

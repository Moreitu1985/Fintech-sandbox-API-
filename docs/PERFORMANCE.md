# Performance

## What is measured

[`load/transfers.js`](../load/transfers.js) is a [k6](https://k6.io) script. The workflow [`load-test.yml`](../.github/workflows/load-test.yml) runs it against the real application (the `prod` profile, PostgreSQL 16) on every pull request or push that changes the code, every Monday, and on demand.

1. **Setup.** Creates 10 tenants through the admin API. Each gets a treasury and 20 customer accounts funded with R10,000.00.
2. **Load.** Starts requests at a constant rate (200 a second for 60 seconds by default), whatever the server's response time, so a slow server shows up as latency and dropped iterations rather than being hidden. 80% are transfers between random accounts of one tenant, each with a fresh idempotency key; 20% are balance reads. Accounts in a tenant share hot rows (the tenant's audit chain head and the account rows), so this exercises the locking design in [ADR 0002](adr/0002-pessimistic-locking.md).
3. **Integrity check.** For every tenant: `GET /v1/ledger/integrity` must report that postings sum to zero, every stored balance matches its postings and the audit chain verifies. The customer balances must still add up to what was funded, and none may be negative.

Rate limits are raised for this test: it measures the ledger, and the limiter has its own tests.

## Pass criteria

| Threshold | Limit |
|---|---|
| Transfer latency p95 | < 500 ms |
| Transfer latency p99 | < 1,000 ms |
| Balance read latency p95 | < 250 ms |
| Unexpected responses (anything but 201, or 422 for insufficient funds) | 0 |
| Rate-limited responses | 0 |
| Iterations dropped because the server fell behind | < 1% |
| Integrity failures after the run | 0 |

## Latest result

GitHub-hosted `ubuntu-latest` runner (shared, 4 vCPUs), API and PostgreSQL on the same machine, default settings:

| Measure | Result |
|---|---|
| Transfers posted | 9,603 (about 160 a second during the load phase) |
| Correctly declined for insufficient funds | 6 |
| Transfer latency p50 / p95 / p99 | 7.0 / 69.7 / 174.2 ms |
| Balance read latency p50 / p95 | 2.1 / 27.9 ms |
| Unexpected responses | 0 |
| Dropped iterations | 0 |
| Integrity failures | 0 |

Every run publishes its own table in the workflow summary, and the raw k6 output as an artifact.

**Caveats.** A shared CI runner is noisy, and the load generator, API and database compete for the same CPUs, so treat these as a floor and a regression check, not a capacity figure. The fraud service is not in the path here; with it on, each transfer adds one scoring call (a few milliseconds over mTLS on the same network).

## Run it yourself

```bash
docker compose up -d --build
docker run --rm --network host -v "$PWD/load:/scripts:ro" \
  -e BASE_URL=http://localhost:8080 -e ADMIN_TOKEN=<your admin token> \
  -e RATE=200 -e DURATION=60s grafana/k6:1.8.2 run /scripts/transfers.js
```

Other settings: `TENANTS` (default 10), `ACCOUNTS` (accounts per tenant, default 20). Raise `LEDGERFORGE_RATELIMIT_CAPACITY` and `LEDGERFORGE_RATELIMIT_REFILLPERSECOND` on the API first, or the limiter will answer 429.

# LedgerForge

**A secure, multi-tenant double-entry ledger API for fintechs: money that can't be lost, duplicated or quietly rewritten.**

Built with Java 21 and Spring Boot 3, backed by PostgreSQL, and tested for the failures that cost real money: double charges on retry, race conditions between concurrent payments, cross-tenant data leaks and tampered records.

> Sandbox platform: LedgerForge moves simulated money with production-grade rules. Real money movement is planned through a licensed partner (see Roadmap).

---

## Why it exists

Every fintech needs a ledger, and most build one from scratch. The hard parts, correctness under concurrency, safe retries and trustworthy audit history, take months and are easy to get subtly wrong. LedgerForge packages those parts as a tested API.

## What it does

| Capability | How |
|---|---|
| Double-entry ledger | Every transfer writes two postings that sum to zero; balances are verifiable against history |
| Safe retries | `Idempotency-Key` header: a retried request returns the original result and never moves money twice |
| Concurrency safety | Row locks taken in a fixed order: no lost updates, no deadlocks, no negative customer balances |
| Multi-tenancy | Each fintech sees only its own data; cross-tenant ids return 404 |
| Tamper-evident audit | Per-tenant SHA-256 hash chain; append-only tables enforced by PostgreSQL triggers |
| Proof on demand | `GET /v1/ledger/integrity` checks that books balance, balances match postings and the audit chain is intact |
| Plugins | `TransferPlugin` beans hook into every transfer without touching the core (example: per-transfer limit) |
| API security | Hashed API keys, constant-time checks, rate limiting, strict headers, RFC 9457 errors with request ids |

## Architecture

```mermaid
flowchart TB
    C[Fintech clients] --> G[Security gateway<br/>API keys · rate limits · validation · request IDs]
    G --> L[Ledger core<br/>accounts · transfers · idempotency]
    L --> P[Plugin host<br/>hooks around every transfer]
    L --> A[Audit chain<br/>SHA-256 linked events]
    L --> DB[(PostgreSQL<br/>append-only history)]
    A --> DB
```

## Quickstart (5 minutes)

**Run locally (in-memory H2 database):**

```bash
export LEDGERFORGE_ADMIN_TOKEN=$(openssl rand -hex 32)
mvn spring-boot:run
```

**Or with PostgreSQL via Docker:**

```bash
cp .env.example .env    # then set real secrets in .env
docker compose up --build
```

**Make your first transfer:**

```bash
# 1. Create a tenant (returns an API key once — store it)
curl -s -X POST localhost:8080/admin/tenants \
  -H "X-Admin-Token: $LEDGERFORGE_ADMIN_TOKEN" -H "Content-Type: application/json" \
  -d '{"name":"Acme Pay"}'
export KEY=lf_test_...   # from the response

# 2. Open a funding account and a customer account
curl -s -X POST localhost:8080/v1/accounts -H "Authorization: Bearer $KEY" \
  -H "Content-Type: application/json" -d '{"name":"Treasury","currency":"ZAR","allowNegative":true}'
curl -s -X POST localhost:8080/v1/accounts -H "Authorization: Bearer $KEY" \
  -H "Content-Type: application/json" -d '{"name":"Thandi","currency":"ZAR"}'

# 3. Move R100.00 (amounts are in cents). Run it twice: the money moves once.
curl -s -X POST localhost:8080/v1/transfers -H "Authorization: Bearer $KEY" \
  -H "Idempotency-Key: first-transfer-001" -H "Content-Type: application/json" \
  -d '{"fromAccountId":"<treasury-id>","toAccountId":"<thandi-id>","amount":10000,"currency":"ZAR"}'

# 4. Prove the books are healthy
curl -s localhost:8080/v1/ledger/integrity -H "Authorization: Bearer $KEY"
```

Interactive API docs (dev profile): <http://localhost:8080/swagger-ui.html>

## API overview

| Method | Path | Purpose |
|---|---|---|
| POST | `/admin/tenants` | Create a tenant and its first API key (admin) |
| POST | `/admin/tenants/{id}/api-keys` | Issue a new key for rotation (admin) |
| DELETE | `/admin/api-keys/{id}` | Revoke a key immediately (admin) |
| POST | `/v1/accounts` | Open an account |
| GET | `/v1/accounts` · `/v1/accounts/{id}` | List accounts · get balance |
| GET | `/v1/accounts/{id}/entries` | Statement, newest first |
| POST | `/v1/transfers` | Transfer (requires `Idempotency-Key`) |
| GET | `/v1/transfers/{id}` | Get a transfer |
| GET | `/v1/ledger/integrity` | Balance and audit-chain proof |
| GET | `/v1/plugins` | Active plugins |

## Tests that prove the guarantees

```bash
mvn verify
```

| Test | What it proves |
|---|---|
| `ConcurrencyTest` | 200 parallel random transfers: total money conserved, no negative balances, integrity healthy |
| `IdempotencyTest` | Retries move money once; reusing a key with a different body is rejected |
| `TenantIsolationTest` | A tenant cannot read, list or move another tenant's money |
| `AuditTamperTest` | Editing a past audit record, or deleting the newest ones, directly in the database is detected at the exact index |
| `SecurityTest` | Missing, malformed, forged and revoked keys are rejected; admin is separated; headers are set |
| `TransferFlowTest` | Insufficient funds, currency mismatch, plugin limits and validation errors behave correctly |

## Design decisions

Short records of each trade-off live in [`docs/adr`](docs/adr):

1. [Double-entry ledger with integer minor units](docs/adr/0001-double-entry-ledger.md)
2. [Pessimistic locking in a fixed order](docs/adr/0002-pessimistic-locking.md)
3. [Hashed, prefixed API keys](docs/adr/0003-api-key-hashing.md)
4. [Hash-chained audit log](docs/adr/0004-hash-chained-audit.md)

Security analysis: [`docs/THREAT_MODEL.md`](docs/THREAT_MODEL.md).

## Roadmap

| Phase | Focus | Status |
|---|---|---|
| 1 | Ledger core, security, tests, Docker, CI | In progress |
| 2 | Python fraud-scoring service as a transfer plugin; LLM assistant endpoint | Planned |
| 3 | OAuth2 + mTLS, Redis rate limits, OpenTelemetry, Testcontainers, Kubernetes, load tests | Planned |
| 4 | Next.js developer dashboard, TypeScript SDK, webhooks | Planned |
| 5 | Licensed-partner connectors (bank, KYC, open banking), POPIA review, SOC 2 readiness | Planned |

## Tech stack

Java 21 · Spring Boot 3.5 · Spring Security · Spring Data JPA / Hibernate · PostgreSQL · Flyway · H2 (tests) · springdoc OpenAPI · JUnit 5 · Docker · GitHub Actions · CodeQL · Dependabot

## License

MIT. See [LICENSE](LICENSE).

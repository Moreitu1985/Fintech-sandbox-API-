# Threat model

Method: STRIDE over the main assets. Updated each phase. Status: **Implemented**, **Partial**, or **Planned** (with phase).

## Assets

1. Account balances and transfer history (integrity is the top priority)
2. Tenant API keys and the admin token
3. Tenant data confidentiality (one fintech must never see another's data)
4. Audit history (evidence for disputes and regulators)
5. Service availability

## Trust boundaries

- Internet → API (all requests are untrusted)
- API → database (the application role is trusted but must not be able to rewrite history)
- Platform admin → `/admin` endpoints

## STRIDE analysis

| Threat | Example | Mitigation | Status |
|---|---|---|---|
| **S**poofing | Stolen or guessed API key | 256-bit random keys; only SHA-256 hashes stored; constant-time comparison; immediate revocation; per-IP rate limit slows guessing | Implemented |
| Information disclosure | Metrics endpoint leaks operational data | `/actuator/prometheus` needs a separate read-only metrics token; tenant keys and the admin token are refused; metric tags never contain ids | Implemented |
| Spoofing | Admin token brute force | 32+ character token enforced at startup; constant-time comparison; rate limited; admin disabled when unset | Implemented |
| Spoofing | Leaked credential replayed | OAuth 2.0 client credentials: clients can send 15-minute, scope-limited RS256 tokens instead of the API key; tokens verified for signature, issuer, audience and expiry (ADR 0008) | Implemented (certificate-bound tokens, RFC 8705, possible next step) |
| **T**ampering | Double charge via retried request | Idempotency keys scoped per tenant with request-hash check | Implemented |
| Tampering | Race condition corrupts balance | Pessimistic row locks in a fixed order; `@Version` as a backstop | Implemented |
| Tampering | Insider edits history in the database | PostgreSQL triggers make transactions, postings and audit append-only (proven against real PostgreSQL in CI by `AppendOnlyHistoryTest`); hash chain detects edits | Implemented |
| Tampering | Attacker with DB superuser recomputes the whole chain | Periodically anchor the latest chain hash outside the database (e.g. signed, timestamped export) | Planned (Phase 3) |
| **R**epudiation | Tenant denies making a transfer | Every change is an audit event tied to tenant and key; request ids in logs | Partial (key id per event in Phase 3) |
| **I**nformation disclosure | Tenant reads another tenant's account | Every query scoped by tenant from the authenticated key; foreign ids return 404 | Implemented |
| Information disclosure | Stack traces or SQL in errors | Problem+json errors with generic messages; stack traces disabled | Implemented |
| Information disclosure | Keys leaked from database backup | Only hashes are stored | Implemented |
| Information disclosure | Traffic interception | HTTPS with HSTS at the edge | Partial (TLS termination in deployment) |
| **D**enial of service | Request flood | Token-bucket limits per key and per IP, shared across instances in Redis (atomic Lua script, Redis clock); API limits fail open and the AI assistant's limit fails closed if Redis is down, behind a circuit breaker (ADR 0007) | Implemented |
| Denial of service | Oversized or malicious input | Bean Validation limits on every field; amount ceiling; paging capped at 100 | Implemented |
| Tampering | Fraudulent transfer from a taken-over account | ML fraud check before money moves: BLOCK refuses, REVIEW is audited with reasons | Implemented (Phase 2) |
| Spoofing | Rogue caller uses the fraud service, or a fake service answers the ledger | Mutual TLS with a CA dedicated to this link (each side verifies the other; no plain-HTTP downgrade; a certificate failure always refuses the transfer), plus a 32+ char service token; service only on the internal network; each container reads only its own key | Implemented (ADR 0008) |
| Denial of service | Fraud service slow or down stalls payments | 500 ms / 1.5 s timeouts; explicit fail-open or fail-closed policy | Implemented |
| Information disclosure | AI assistant reveals another tenant's data | Tools take the tenant from the API key, never from the model; foreign ids look missing; no free-form SQL | Implemented (Phase 2) |
| Tampering / Elevation | Prompt injection in account names, descriptions or fraud reasons steers the assistant | Only read-only tools exist; results wrapped as untrusted data; links and images stripped from answers; output goes only to the same caller | Implemented |
| Denial of service | AI assistant used to run up model costs | 10 questions/min per tenant, 8 in flight per server, 5 model calls and 4 tool calls per round, 60k characters of tool data, capped output, 60 s deadline; off by default | Implemented |
| **E**levation of privilege | Tenant key used on admin endpoints | Separate roles; admin requires the admin token | Implemented |
| Elevation of privilege | A client needing read access holds write access | OAuth scopes `ledger:read`, `ledger:write`, `assistant` (which also needs `ledger:read`) enforced per route and method; a token carries only the scopes requested | Implemented |
| Elevation of privilege | Unknown or forgotten endpoints exposed | Default-deny authorization rule; only health is public | Implemented |

## OWASP API Security Top 10 (2023)

| Risk | Coverage |
|---|---|
| API1 Broken object level authorization | Tenant scoping on every lookup; `TenantIsolationTest` |
| API2 Broken authentication | Hashed keys, revocation, `SecurityTest` |
| API3 Broken object property level authorization | DTOs only; entities never serialized; `allowNegative` fixed at creation |
| API4 Unrestricted resource consumption | Rate limits, page caps, validation |
| API5 Broken function level authorization | `/admin` vs `/v1` roles; default deny |
| API6 Unrestricted access to sensitive business flows | Per-transfer limit plugin; ML fraud-check plugin (BLOCK / REVIEW) |
| API7 Server-side request forgery | No user-supplied URLs are fetched (webhooks in Phase 4 will use an allow-list) |
| API8 Security misconfiguration | Secure headers, no default users, Swagger off in prod, CodeQL in CI |
| API9 Improper inventory management | Versioned `/v1` paths, OpenAPI spec |
| API10 Unsafe consumption of APIs | Fraud-service responses are typed and validated, with timeouts and a defined failure policy; partner connectors follow the same rule (Phase 5) |

## Known gaps (honest list)

- If Redis is down, API rate limits are not enforced (fail-open by design, visible in `ledgerforge_rate_limiter_errors_total`); the AI assistant refuses questions instead.
- The audit chain proves tampering but cannot stop a full database administrator from rewriting it; external anchoring is planned.
- No end-user (customer) authentication: tenants authenticate, and they are responsible for their own users.
- Two Spring Framework 6.2 findings (CVE-2026-47884, CVE-2026-47890) are fixed only in Spring Framework 7. They are accepted in `.trivyignore` until 2027-01-31, with the Spring Boot 4 migration planned for Phase 3. Every other fixable CRITICAL/HIGH finding fails the build.
- The fraud model is trained on synthetic data and defaults to fail-open in the sandbox. Real deployments need retraining on real labelled data and a fail-closed or fallback policy.
- The AI assistant sends the tenant's own ledger data to an external model provider. Production use needs a data-processing agreement and a POPIA review.
- Revoking an API key doesn't recall access tokens already issued from it; they expire within 15 minutes.

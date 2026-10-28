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
| Spoofing | Admin token brute force | 32+ character token enforced at startup; constant-time comparison; rate limited; admin disabled when unset | Implemented |
| Spoofing | Key replay from a compromised client | mTLS-bound OAuth2 client credentials | Planned (Phase 3) |
| **T**ampering | Double charge via retried request | Idempotency keys scoped per tenant with request-hash check | Implemented |
| Tampering | Race condition corrupts balance | Pessimistic row locks in a fixed order; `@Version` as a backstop | Implemented |
| Tampering | Insider edits history in the database | PostgreSQL triggers make transactions, postings and audit append-only; hash chain detects edits | Implemented |
| Tampering | Attacker with DB superuser recomputes the whole chain | Periodically anchor the latest chain hash outside the database (e.g. signed, timestamped export) | Planned (Phase 3) |
| **R**epudiation | Tenant denies making a transfer | Every change is an audit event tied to tenant and key; request ids in logs | Partial (key id per event in Phase 3) |
| **I**nformation disclosure | Tenant reads another tenant's account | Every query scoped by tenant from the authenticated key; foreign ids return 404 | Implemented |
| Information disclosure | Stack traces or SQL in errors | Problem+json errors with generic messages; stack traces disabled | Implemented |
| Information disclosure | Keys leaked from database backup | Only hashes are stored | Implemented |
| Information disclosure | Traffic interception | HTTPS with HSTS at the edge | Partial (TLS termination in deployment) |
| **D**enial of service | Request flood | Token-bucket limits per key and per IP | Implemented (per instance; Redis in Phase 3) |
| Denial of service | Oversized or malicious input | Bean Validation limits on every field; amount ceiling; paging capped at 100 | Implemented |
| **E**levation of privilege | Tenant key used on admin endpoints | Separate roles; admin requires the admin token | Implemented |
| Elevation of privilege | Unknown or forgotten endpoints exposed | Default-deny authorization rule; only health is public | Implemented |

## OWASP API Security Top 10 (2023)

| Risk | Coverage |
|---|---|
| API1 Broken object level authorization | Tenant scoping on every lookup; `TenantIsolationTest` |
| API2 Broken authentication | Hashed keys, revocation, `SecurityTest` |
| API3 Broken object property level authorization | DTOs only; entities never serialized; `allowNegative` fixed at creation |
| API4 Unrestricted resource consumption | Rate limits, page caps, validation |
| API5 Broken function level authorization | `/admin` vs `/v1` roles; default deny |
| API6 Unrestricted access to sensitive business flows | Per-transfer limit plugin; fraud plugin in Phase 2 |
| API7 Server-side request forgery | No user-supplied URLs are fetched (webhooks in Phase 4 will use an allow-list) |
| API8 Security misconfiguration | Secure headers, no default users, Swagger off in prod, CodeQL in CI |
| API9 Improper inventory management | Versioned `/v1` paths, OpenAPI spec |
| API10 Unsafe consumption of APIs | Partner responses validated in connector plugins (Phase 5) |

## Known gaps (honest list)

- Rate limits are per instance until Redis is added.
- The audit chain proves tampering but cannot stop a full database administrator from rewriting it; external anchoring is planned.
- No end-user (customer) authentication: tenants authenticate, and they are responsible for their own users.
- Two Spring Framework 6.2 findings (CVE-2026-47884, CVE-2026-47890) are fixed only in Spring Framework 7. They are accepted in `.trivyignore` until 2027-01-31, with the Spring Boot 4 migration planned for Phase 3. Every other fixable CRITICAL/HIGH finding fails the build.

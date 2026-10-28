# ADR 0002: Pessimistic locking in a fixed order

**Status:** Accepted

## Context

Two transfers touching the same account at the same moment can both read the old balance and both succeed, allowing overdrafts or lost updates.

## Options

1. **Optimistic locking only** (`@Version`): cheap when contention is low, but clients get conflict errors under load and must retry.
2. **Pessimistic row locks** (`SELECT ... FOR UPDATE`): waits instead of failing; simple to reason about.
3. **Single-writer queue per account**: highest throughput, much more infrastructure.

## Decision

Option 2, with `@Version` kept as a safety net.

- Every write path takes locks in one global order: **tenant row first**, then accounts in ascending UUID order. A single order means no two transactions can wait on each other in a cycle.
- Locking the tenant first also matters on PostgreSQL: inserts that reference `tenants(id)` take a `FOR KEY SHARE` lock, which would conflict with a later `FOR UPDATE` on the same row.
- The idempotency key is checked only after the tenant lock is held, so two requests with the same key are serialised and the second always gets a clean replay or `IDEMPOTENCY_KEY_REUSED`.
- A unique constraint on `(tenant_id, idem_key)` remains as a backstop.

## Consequences

- Writes within one tenant are serialised by the tenant lock. Different tenants run fully in parallel. This is acceptable for a sandbox and will be measured in Phase 3 load tests; if it becomes the bottleneck, the audit chain can move to per-account chains or an asynchronous outbox, and the account-level locks already in place take over.
- `ConcurrencyTest` verifies the money invariants under 200 parallel transfers.

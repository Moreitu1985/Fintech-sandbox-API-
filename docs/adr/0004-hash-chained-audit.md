# ADR 0004: Hash-chained audit log

**Status:** Accepted

## Context

Financial records must show if history was altered, including by someone with database access.

## Decision

- Each tenant has its own chain. Event `n` stores `hash = SHA-256(prevHash, tenantId, n, type, timestamp, payload)`, with event 0 linking to a fixed genesis value.
- Events are appended inside the same database transaction as the change they record, so the log and the ledger never disagree.
- The tenant row is locked during an append, and `UNIQUE (tenant_id, chain_index)` makes a forked chain impossible.
- The tenant row stores the chain head (latest index and hash). Without it, deleting the newest events would leave a shorter chain whose links still verify.
- On PostgreSQL, triggers reject `UPDATE` and `DELETE` on audit events, postings and transactions.
- `GET /v1/ledger/integrity` re-verifies the whole chain and reports the first broken index.

## Consequences

- Editing, deleting or reordering any event, including the newest ones, is detected (`AuditTamperTest`).
- Someone with full database control could rebuild the entire chain. The planned mitigation is to anchor the latest hash outside the database on a schedule (Phase 3).
- Full-chain verification is O(n). For large tenants, verification will checkpoint signed chain heads.

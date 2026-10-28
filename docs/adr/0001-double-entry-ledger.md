# ADR 0001: Double-entry ledger with integer minor units

**Status:** Accepted

## Context

A ledger must be able to prove, at any time, that no money was created or destroyed. Floating-point arithmetic cannot represent most decimal amounts exactly.

## Decision

- Every transfer writes two postings: `-amount` on the source and `+amount` on the destination. Across a tenant, postings always sum to zero.
- Amounts are `BIGINT` minor units (cents). No `double`, no `BigDecimal` rounding modes to get wrong. Overflow throws (`Math.addExact`).
- Each account keeps a cached `balance` for fast reads, updated in the same database transaction as its postings. The integrity endpoint checks that the cache equals the sum of postings.
- History is immutable. Mistakes are corrected with a new, reversing transfer.
- Funding money enters through accounts flagged `allowNegative` (a treasury or settlement account), the way a real bank's settlement account works.

## Consequences

- Multi-currency transfers need an explicit FX design (two linked transfers through FX accounts), not supported yet.
- Amounts above 9.2 × 10^18 minor units are impossible, which is far beyond any realistic need.

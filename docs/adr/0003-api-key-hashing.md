# ADR 0003: Hashed, prefixed API keys

**Status:** Accepted

## Decision

- Format: `lf_test_<12 hex prefix>_<64 hex secret>`. The `lf_test_` marker makes leaked keys easy to spot with secret scanners and leaves room for `lf_live_` later.
- The prefix is a public lookup handle with a unique index. The database stores only `SHA-256(full key)`.
- Verification compares hashes with `MessageDigest.isEqual` (constant time).
- The plaintext key is shown once, at creation. Rotation means issuing a new key, then revoking the old one.

## Why SHA-256 and not bcrypt or Argon2

Slow password hashes protect low-entropy human passwords from guessing. These keys carry 256 bits of randomness, so guessing is already infeasible, and a fast hash keeps per-request authentication cheap. This is the same approach used by major payment APIs.

## Consequences

- A database leak does not expose usable keys.
- Unknown, malformed and revoked keys produce the same `401`, revealing nothing to an attacker.

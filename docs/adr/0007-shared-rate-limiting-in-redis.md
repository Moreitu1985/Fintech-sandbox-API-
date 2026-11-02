# ADR 0007: Rate limits shared across instances in Redis

**Status:** Accepted

## Context

With in-memory buckets, each API instance counts on its own: behind a load balancer with three instances, a client gets three times its limit, and a restart resets everyone's budget. Production runs more than one instance.

## Decision

- Rate limiting sits behind a `RateLimiter` interface with two stores, chosen by `RATE_LIMIT_STORE`: `memory` (one instance, development, tests) and `redis` (production).
- The Redis store keeps one token bucket per API key (or per IP when unauthenticated, and per tenant for the AI assistant).
- **Atomic.** Refill, check and take run as one Lua script, which Redis executes atomically, so two instances can never both spend the last token.
- **One clock.** The script reads time from Redis (`TIME`), so clock skew between instances can't create extra tokens.
- **Self-cleaning.** Each bucket expires once it would be full again (capped at a day).
- **Fail-open.** If Redis is unreachable, requests are allowed and a warning is logged at most once a minute. Rate limiting protects capacity; it is not an access control, so an outage of the limiter should not become an outage of the API. Authentication, tenant isolation and the ledger's own checks are unaffected.
- Redis runs with a password, isn't published outside the Docker network, and holds no customer data, only counters.

## Consequences

- One Redis round trip (sub-millisecond on the same network) per request.
- During a Redis outage, limits are not enforced. `ledgerforge_rate_limited_total` falling to zero, and the Redis health check, make that visible.
- Tests prove the shared limit with two separate connections standing in for two instances, against real Redis in Docker.

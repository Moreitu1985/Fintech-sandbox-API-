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
- **Each caller chooses fail-open or fail-closed.** API request limits fail open: rate limiting protects capacity, not access, so a limiter outage should not become an API outage (authentication, tenant isolation and the ledger's own checks are unaffected). The AI assistant's per-tenant limit fails closed, because it caps paid model calls.
- **Cheap outages.** A circuit breaker skips Redis for 5 seconds after a failure, and Lettuce rejects commands at once while disconnected (connect timeout 300 ms, command timeout 500 ms), so an outage costs one timeout, not one per request.
- **Visible outages.** Every failure increments `ledgerforge_rate_limiter_errors_total`; a warning is logged at most once a minute. The Redis health check stays off by default so the load balancer doesn't remove healthy instances during a Redis outage.
- **Valid settings only.** Capacity below 1 or a refill rate of 0 is refused at startup, since it would lock clients out while telling them to retry.
- Requires **Redis 7 or newer** (the script reads `TIME` before writing, which needs effects replication).
- Redis runs with a password, isn't published outside the Docker network, and holds no customer data, only counters.

## Consequences

- One Redis round trip (sub-millisecond on the same network) per request.
- During a Redis outage, API request limits are not enforced and assistant questions are refused, both visible in `ledgerforge_rate_limiter_errors_total`.
- Tests prove the shared limit with two separate connections standing in for two instances, against real Redis in Docker.

package io.ledgerforge.security.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Token buckets in Redis, shared by every API instance, so N instances enforce one limit rather than N.
 *
 * The whole read-refill-take-write runs as one Lua script, which Redis executes atomically, so two
 * instances can never both spend the last token. Time comes from the Redis server (TIME), not the
 * instances' clocks, so clock skew between instances can't mint extra tokens. Idle buckets expire.
 *
 * If Redis is unreachable, the caller decides: the API's request limits fail OPEN (rate limiting protects
 * capacity, so a limiter outage should not become an API outage), while limits that cap spending, such
 * as the AI assistant's, fail CLOSED. A circuit breaker then skips Redis for a few seconds, so an outage
 * costs one timeout rather than one per request. Every failure increments an error metric.
 */
public class RedisRateLimiter implements RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimiter.class);
    static final String KEY_PREFIX = "ledgerforge:rl:";

    // KEYS[1] bucket key; ARGV[1] capacity; ARGV[2] refill tokens per millisecond.
    // Returns {allowed (1/0), milliseconds until a token is available}.
    private static final String LUA = """
            local capacity = tonumber(ARGV[1])
            local rate = tonumber(ARGV[2])
            local t = redis.call('TIME')
            local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
            local data = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
            local tokens = tonumber(data[1])
            local ts = tonumber(data[2])
            if tokens == nil or ts == nil then
              tokens = capacity
              ts = now
            end
            tokens = math.min(capacity, tokens + math.max(0, now - ts) * rate)
            local allowed = 0
            local wait = 0
            if tokens >= 1 then
              tokens = tokens - 1
              allowed = 1
            else
              wait = math.ceil((1 - tokens) / rate)
            end
            redis.call('HSET', KEYS[1], 'tokens', tostring(tokens), 'ts', tostring(now))
            -- TTL capped at a day: Lua formats huge numbers as 1e+15, which PEXPIRE would reject.
            redis.call('PEXPIRE', KEYS[1], math.min(86400000, math.ceil(capacity / rate) + 1000))
            return {allowed, wait}
            """;

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> SCRIPT = RedisScript.of(LUA, List.class);

    /** After a failure, Redis is skipped for this long, so an outage costs one timeout, not one per request. */
    static final long BREAKER_OPEN_MILLIS = 5_000;
    /** Retry-After given when a fail-closed caller is refused because Redis is unavailable. */
    static final long UNAVAILABLE_RETRY_SECONDS = 5;

    private final StringRedisTemplate redis;
    private final Runnable onError;
    private final AtomicLong breakerOpenUntil = new AtomicLong();
    private final AtomicLong lastWarning = new AtomicLong();

    /** @param onError called on every failure or skipped call (e.g. increments an error metric) */
    public RedisRateLimiter(StringRedisTemplate redis, Runnable onError) {
        this.redis = redis;
        this.onError = onError;
    }

    public RedisRateLimiter(StringRedisTemplate redis) {
        this(redis, () -> { });
    }

    @Override
    public Decision tryAcquire(String key, long capacity, double refillPerSecond, boolean failOpen) {
        if (System.currentTimeMillis() < breakerOpenUntil.get()) {
            onError.run();
            return unavailable(failOpen);
        }
        try {
            List<?> result = redis.execute(SCRIPT, List.of(KEY_PREFIX + key),
                    Long.toString(capacity), Double.toString(refillPerSecond / 1000.0));
            if (result == null || result.size() != 2) {
                throw new IllegalStateException("unexpected rate-limit script result: " + result);
            }
            boolean allowed = ((Number) result.get(0)).longValue() == 1L;
            long waitMs = ((Number) result.get(1)).longValue();
            return allowed ? Decision.ALLOW : new Decision(false, Math.max(1, (waitMs + 999) / 1000));
        } catch (RuntimeException e) {
            long now = System.currentTimeMillis();
            breakerOpenUntil.set(now + BREAKER_OPEN_MILLIS);
            onError.run();
            long last = lastWarning.get();
            if (now - last > 60_000 && lastWarning.compareAndSet(last, now)) {
                log.warn("Redis rate limiter unavailable; skipping Redis for {} ms (fail-open callers allowed, "
                        + "fail-closed callers refused): {}", BREAKER_OPEN_MILLIS, e.getMessage());
            }
            return unavailable(failOpen);
        }
    }

    private static Decision unavailable(boolean failOpen) {
        return failOpen ? Decision.ALLOW : new Decision(false, UNAVAILABLE_RETRY_SECONDS);
    }
}

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
 * If Redis is unreachable the limiter fails OPEN (the request is allowed and a warning is logged):
 * rate limiting protects capacity, and an outage of the limiter should not become an outage of the API.
 * Authentication, validation and the ledger's own checks are unaffected.
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

    private final StringRedisTemplate redis;
    private final AtomicLong lastWarning = new AtomicLong();

    public RedisRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Decision tryAcquire(String key, long capacity, double refillPerSecond) {
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
            long last = lastWarning.get();
            if (now - last > 60_000 && lastWarning.compareAndSet(last, now)) {
                log.warn("Redis rate limiter unavailable, allowing requests (fail-open): {}", e.getMessage());
            }
            return Decision.ALLOW;
        }
    }
}

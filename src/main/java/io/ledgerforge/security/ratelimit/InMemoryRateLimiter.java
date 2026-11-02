package io.ledgerforge.security.ratelimit;

import io.ledgerforge.security.TokenBucket;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Buckets in this JVM only: right for one instance, local development and tests. */
public class InMemoryRateLimiter implements RateLimiter {

    private static final int MAX_TRACKED_KEYS = 100_000;
    private final Map<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    @Override
    public Decision tryAcquire(String key, long capacity, double refillPerSecond, boolean failOpen) {
        if (buckets.size() > MAX_TRACKED_KEYS) {
            buckets.clear(); // bound memory under a flood of distinct clients
        }
        TokenBucket bucket = buckets.computeIfAbsent(key, k -> new TokenBucket(capacity, refillPerSecond));
        return bucket.tryConsume() ? Decision.ALLOW : new Decision(false, bucket.secondsUntilNextToken());
    }
}

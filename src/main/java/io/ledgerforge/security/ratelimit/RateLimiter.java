package io.ledgerforge.security.ratelimit;

/** Token-bucket rate limiting behind one interface, so the store (memory or Redis) is a config choice. */
public interface RateLimiter {

    /** @param retryAfterSeconds when not allowed, whole seconds until one request would be */
    record Decision(boolean allowed, long retryAfterSeconds) {
        public static final Decision ALLOW = new Decision(true, 0);
    }

    /** Takes one token from the bucket named {@code key}, creating it full if new. */
    Decision tryAcquire(String key, long capacity, double refillPerSecond);
}

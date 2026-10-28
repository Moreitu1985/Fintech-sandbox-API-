package io.ledgerforge.security;

import java.util.function.LongSupplier;

/** Thread-safe token bucket. The clock is injectable so tests don't need to sleep. */
public final class TokenBucket {

    private final double capacity;
    private final double refillPerNano;
    private final LongSupplier nanoClock;
    private double tokens;
    private long lastRefill;

    public TokenBucket(long capacity, double refillPerSecond, LongSupplier nanoClock) {
        if (capacity < 1 || refillPerSecond <= 0) {
            throw new IllegalArgumentException("capacity must be >= 1 and refill rate > 0");
        }
        this.capacity = capacity;
        this.refillPerNano = refillPerSecond / 1_000_000_000d;
        this.nanoClock = nanoClock;
        this.tokens = capacity;
        this.lastRefill = nanoClock.getAsLong();
    }

    public TokenBucket(long capacity, double refillPerSecond) {
        this(capacity, refillPerSecond, System::nanoTime);
    }

    public synchronized boolean tryConsume() {
        refill();
        if (tokens >= 1) {
            tokens -= 1;
            return true;
        }
        return false;
    }

    /** Seconds until one token is available, rounded up (for the Retry-After header). */
    public synchronized long secondsUntilNextToken() {
        refill();
        if (tokens >= 1) {
            return 0;
        }
        double nanos = (1 - tokens) / refillPerNano;
        return Math.max(1, (long) Math.ceil(nanos / 1_000_000_000d));
    }

    private void refill() {
        long now = nanoClock.getAsLong();
        long elapsed = now - lastRefill;
        if (elapsed > 0) {
            tokens = Math.min(capacity, tokens + elapsed * refillPerNano);
            lastRefill = now;
        }
    }
}

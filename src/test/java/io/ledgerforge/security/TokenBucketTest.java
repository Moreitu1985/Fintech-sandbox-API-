package io.ledgerforge.security;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class TokenBucketTest {

    @Test
    void allowsBurstThenBlocksThenRefills() {
        AtomicLong clock = new AtomicLong(0);
        TokenBucket bucket = new TokenBucket(3, 1.0, clock::get); // 3 burst, 1 token/second

        assertThat(bucket.tryConsume()).isTrue();
        assertThat(bucket.tryConsume()).isTrue();
        assertThat(bucket.tryConsume()).isTrue();
        assertThat(bucket.tryConsume()).isFalse();
        assertThat(bucket.secondsUntilNextToken()).isEqualTo(1);

        clock.addAndGet(1_000_000_000L); // one second later
        assertThat(bucket.tryConsume()).isTrue();
        assertThat(bucket.tryConsume()).isFalse();
    }

    @Test
    void neverExceedsCapacity() {
        AtomicLong clock = new AtomicLong(0);
        TokenBucket bucket = new TokenBucket(2, 10.0, clock::get);
        clock.addAndGet(60_000_000_000L); // a minute idle
        assertThat(bucket.tryConsume()).isTrue();
        assertThat(bucket.tryConsume()).isTrue();
        assertThat(bucket.tryConsume()).isFalse();
    }
}

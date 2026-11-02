package io.ledgerforge.security.ratelimit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class RedisRateLimiterTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    static LettuceConnectionFactory factoryA;
    static LettuceConnectionFactory factoryB;

    static LettuceConnectionFactory connect(String host, int port) {
        var config = new RedisStandaloneConfiguration(host, port);
        var client = LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(500)).build();
        var factory = new LettuceConnectionFactory(config, client);
        factory.afterPropertiesSet();
        factory.start();
        return factory;
    }

    @BeforeAll
    static void setUp() {
        factoryA = connect(REDIS.getHost(), REDIS.getMappedPort(6379));
        factoryB = connect(REDIS.getHost(), REDIS.getMappedPort(6379));
    }

    @AfterAll
    static void tearDown() {
        factoryA.destroy();
        factoryB.destroy();
    }

    private static RedisRateLimiter limiter(LettuceConnectionFactory f) {
        return new RedisRateLimiter(new StringRedisTemplate(f));
    }

    private static String key() {
        return "test:" + UUID.randomUUID();
    }

    @Test
    void allowsTheBurstThenRefusesWithRetryAfter() {
        RedisRateLimiter limiter = limiter(factoryA);
        String key = key();
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryAcquire(key, 3, 0.5).allowed()).isTrue();
        }
        RateLimiter.Decision refused = limiter.tryAcquire(key, 3, 0.5);
        assertThat(refused.allowed()).isFalse();
        assertThat(refused.retryAfterSeconds()).isBetween(1L, 2L); // 0.5 tokens/s: about 2 s for one token
    }

    @Test
    void twoInstancesShareOneLimit() {
        // Two separate connections stand in for two API instances behind a load balancer.
        RedisRateLimiter instanceA = limiter(factoryA);
        RedisRateLimiter instanceB = limiter(factoryB);
        String key = key();
        assertThat(instanceA.tryAcquire(key, 4, 0.001).allowed()).isTrue();
        assertThat(instanceB.tryAcquire(key, 4, 0.001).allowed()).isTrue();
        assertThat(instanceA.tryAcquire(key, 4, 0.001).allowed()).isTrue();
        assertThat(instanceB.tryAcquire(key, 4, 0.001).allowed()).isTrue();
        assertThat(instanceA.tryAcquire(key, 4, 0.001).allowed()).as("limit is global, not per instance").isFalse();
        assertThat(instanceB.tryAcquire(key, 4, 0.001).allowed()).isFalse();
    }

    @Test
    void bucketsRefill() throws InterruptedException {
        RedisRateLimiter limiter = limiter(factoryA);
        String key = key();
        assertThat(limiter.tryAcquire(key, 1, 20).allowed()).isTrue();
        assertThat(limiter.tryAcquire(key, 1, 20).allowed()).isFalse();
        Thread.sleep(120); // 20 tokens/s: one token every 50 ms
        assertThat(limiter.tryAcquire(key, 1, 20).allowed()).isTrue();
    }

    @Test
    void keysAreIndependentAndExpire() {
        RedisRateLimiter limiter = limiter(factoryA);
        String a = key();
        String b = key();
        assertThat(limiter.tryAcquire(a, 1, 0.001).allowed()).isTrue();
        assertThat(limiter.tryAcquire(a, 1, 0.001).allowed()).isFalse();
        assertThat(limiter.tryAcquire(b, 1, 0.001).allowed()).isTrue();
        Long ttl = new StringRedisTemplate(factoryA).getExpire(RedisRateLimiter.KEY_PREFIX + a);
        assertThat(ttl).isPositive().isLessThanOrEqualTo(86_400L);
    }

    @Test
    void failsOpenOrClosedAsAskedAndTripsTheBreaker() {
        LettuceConnectionFactory dead = connect("127.0.0.1", 1); // nothing listens on port 1
        AtomicInteger errors = new AtomicInteger();
        try {
            RedisRateLimiter limiter = new RedisRateLimiter(new StringRedisTemplate(dead), errors::incrementAndGet);
            assertThat(limiter.tryAcquire(key(), 1, 0.001, true).allowed()).as("fail-open").isTrue();

            long start = System.nanoTime();
            RateLimiter.Decision closed = limiter.tryAcquire(key(), 1, 0.001, false);
            long tookMs = (System.nanoTime() - start) / 1_000_000;
            assertThat(closed.allowed()).as("fail-closed").isFalse();
            assertThat(closed.retryAfterSeconds()).isEqualTo(RedisRateLimiter.UNAVAILABLE_RETRY_SECONDS);
            assertThat(tookMs).as("breaker open: Redis not called again").isLessThan(50);
            assertThat(errors.get()).isEqualTo(2);
        } finally {
            dead.destroy();
        }
    }
}

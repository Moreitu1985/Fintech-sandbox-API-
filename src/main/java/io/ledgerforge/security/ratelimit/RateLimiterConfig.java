package io.ledgerforge.security.ratelimit;

import io.ledgerforge.common.LedgerMetrics;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.redis.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

/** ledgerforge.rate-limit.store: memory (default) or redis. */
@Configuration(proxyBeanMethods = false)
public class RateLimiterConfig {

    @Bean
    @ConditionalOnProperty(name = "ledgerforge.rate-limit.store", havingValue = "memory", matchIfMissing = true)
    RateLimiter inMemoryRateLimiter() {
        return new InMemoryRateLimiter();
    }

    @Bean
    @ConditionalOnProperty(name = "ledgerforge.rate-limit.store", havingValue = "redis")
    RateLimiter redisRateLimiter(StringRedisTemplate redis, LedgerMetrics metrics) {
        return new RedisRateLimiter(redis, metrics::rateLimiterError);
    }

    /** While disconnected, fail commands at once instead of queueing them until the command timeout. */
    @Bean
    @ConditionalOnProperty(name = "ledgerforge.rate-limit.store", havingValue = "redis")
    LettuceClientConfigurationBuilderCustomizer rejectCommandsWhileDisconnected(RedisProperties redis) {
        // Replacing ClientOptions also replaces Boot's socket options, so the connect timeout is set here too.
        Duration connectTimeout = redis.getConnectTimeout() != null ? redis.getConnectTimeout() : Duration.ofMillis(300);
        return builder -> builder.clientOptions(ClientOptions.builder()
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .autoReconnect(true)
                .socketOptions(SocketOptions.builder().connectTimeout(connectTimeout).build())
                .build());
    }
}

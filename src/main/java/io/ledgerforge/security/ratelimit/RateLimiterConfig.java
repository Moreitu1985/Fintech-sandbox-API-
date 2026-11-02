package io.ledgerforge.security.ratelimit;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

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
    RateLimiter redisRateLimiter(StringRedisTemplate redis) {
        return new RedisRateLimiter(redis);
    }
}

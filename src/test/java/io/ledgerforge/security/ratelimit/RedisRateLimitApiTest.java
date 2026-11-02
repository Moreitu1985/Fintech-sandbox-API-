package io.ledgerforge.security.ratelimit;

import io.ledgerforge.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The whole API with store=redis: the filter refuses over-limit requests using the shared buckets. */
@Testcontainers(disabledWithoutDocker = true)
@Import(RedisRateLimitApiTest.RedisContainerConfig.class)
@TestPropertySource(properties = {
        "ledgerforge.rate-limit.store=redis",
        "ledgerforge.rate-limit.capacity=3",
        "ledgerforge.rate-limit.refill-per-second=0.001"})
class RedisRateLimitApiTest extends IntegrationTestBase {

    @TestConfiguration(proxyBeanMethods = false)
    static class RedisContainerConfig {
        @Bean
        @ServiceConnection(name = "redis")
        GenericContainer<?> redis() {
            return new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);
        }
    }

    @Autowired
    private RateLimiter limiter;

    @Test
    void usesRedisAndRefusesOverLimitRequests() throws Exception {
        assertThat(limiter).isInstanceOf(RedisRateLimiter.class);
        for (int i = 0; i < 3; i++) {
            mvc.perform(get("/v1/accounts")).andExpect(status().isUnauthorized()); // counted per IP
        }
        mvc.perform(get("/v1/accounts"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
        mvc.perform(get("/actuator/health")).andExpect(status().isOk()); // health is never limited
    }
}

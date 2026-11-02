package io.ledgerforge.common;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Business metrics, exported at /actuator/prometheus next to Spring's own HTTP, JVM and database metrics.
 * Tags are small fixed sets (outcome, error code, decision); never ids, so cardinality stays bounded.
 */
@Component
public class LedgerMetrics {

    private final MeterRegistry registry;

    public LedgerMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** outcome: posted, replayed or rejected; reason: the error code for rejections, else "none". */
    public void transfer(String outcome, String reason) {
        Counter.builder("ledgerforge.transfers")
                .description("Transfer requests by outcome")
                .tag("outcome", outcome)
                .tag("reason", reason)
                .register(registry)
                .increment();
    }

    /** decision: ALLOW, REVIEW, BLOCK, unavailable or error. */
    public void fraudDecision(String decision) {
        Counter.builder("ledgerforge.fraud.decisions")
                .description("Fraud-model decisions on customer transfers")
                .tag("decision", decision)
                .register(registry)
                .increment();
    }

    /** outcome: complete, incomplete or error:CODE. */
    public void assistantQuestion(String outcome, long inputTokens, long outputTokens) {
        Counter.builder("ledgerforge.assistant.questions").tag("outcome", outcome).register(registry).increment();
        Counter.builder("ledgerforge.assistant.tokens").description("Claude tokens used by the assistant")
                .tag("direction", "input").register(registry).increment(inputTokens);
        Counter.builder("ledgerforge.assistant.tokens").description("Claude tokens used by the assistant")
                .tag("direction", "output").register(registry).increment(outputTokens);
    }

    /** client: api_key or ip. */
    public void rateLimited(String client) {
        Counter.builder("ledgerforge.rate_limited")
                .description("Requests refused by the rate limiter")
                .tag("client", client)
                .register(registry)
                .increment();
    }

    /** The shared rate-limit store failed or was skipped by its circuit breaker. */
    public void rateLimiterError() {
        Counter.builder("ledgerforge.rate_limiter.errors")
                .description("Rate-limit store failures (fail-open or fail-closed decisions taken without Redis)")
                .register(registry)
                .increment();
    }
}

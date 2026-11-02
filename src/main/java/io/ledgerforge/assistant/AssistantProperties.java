package io.ledgerforge.assistant;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Settings for the AI ledger assistant. Disabled by default; when enabled an Anthropic API key is
 * required (checked at startup by {@link AnthropicClient}); limits are validated by {@link AssistantService}.
 *
 * @param maxToolRounds     upper bound on model calls per question (cost and loop control)
 * @param requestsPerMinute per-tenant limit on questions, separate from the general API rate limit
 * @param maxConcurrent     questions in flight across this server, so slow model calls can't exhaust threads
 * @param deadline          overall time budget for one question
 */
@ConfigurationProperties("ledgerforge.assistant")
public record AssistantProperties(
        boolean enabled,
        String apiKey,
        @DefaultValue("claude-haiku-5-5") String model,
        @DefaultValue("https://api.anthropic.com") String baseUrl,
        @DefaultValue("1024") int maxTokens,
        @DefaultValue("5") int maxToolRounds,
        @DefaultValue("10") int requestsPerMinute,
        @DefaultValue("8") int maxConcurrent,
        @DefaultValue("60s") Duration deadline,
        @DefaultValue("2s") Duration connectTimeout,
        @DefaultValue("30s") Duration readTimeout) {
}

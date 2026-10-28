package io.ledgerforge.fraud;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Settings for the fraud-check plugin. Disabled by default; when enabled, url and a 32+ character
 * token are required (checked at startup by {@link FraudCheckPlugin}).
 *
 * @param failOpen if true, a fraud-service outage lets transfers through (logged); if false they are refused
 */
@ConfigurationProperties("ledgerforge.plugins.fraud")
public record FraudProperties(
        boolean enabled,
        String url,
        String token,
        @DefaultValue("true") boolean failOpen,
        @DefaultValue("500ms") Duration connectTimeout,
        @DefaultValue("1500ms") Duration readTimeout) {

    public static final int MIN_TOKEN_LENGTH = 32;
}

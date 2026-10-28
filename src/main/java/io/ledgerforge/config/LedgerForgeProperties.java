package io.ledgerforge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("ledgerforge")
public record LedgerForgeProperties(String adminToken, @DefaultValue RateLimit rateLimit) {

    public static final int MIN_ADMIN_TOKEN_LENGTH = 32;

    public LedgerForgeProperties {
        // Fail fast on a weak admin secret rather than run with it.
        if (adminToken != null && !adminToken.isBlank() && adminToken.length() < MIN_ADMIN_TOKEN_LENGTH) {
            throw new IllegalArgumentException(
                    "ledgerforge.admin-token must be at least " + MIN_ADMIN_TOKEN_LENGTH + " characters");
        }
    }

    public boolean adminEnabled() {
        return adminToken != null && !adminToken.isBlank();
    }

    public record RateLimit(@DefaultValue("100") long capacity, @DefaultValue("50") double refillPerSecond) {
    }
}

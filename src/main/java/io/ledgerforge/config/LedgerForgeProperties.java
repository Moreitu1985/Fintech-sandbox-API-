package io.ledgerforge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param adminToken   platform-operator secret for /admin (32+ chars; empty disables admin)
 * @param metricsToken read-only secret for /actuator/prometheus and /actuator/metrics (32+ chars; empty disables scraping)
 */
@ConfigurationProperties("ledgerforge")
public record LedgerForgeProperties(String adminToken, String metricsToken, @DefaultValue RateLimit rateLimit) {

    public static final int MIN_ADMIN_TOKEN_LENGTH = 32;

    public LedgerForgeProperties {
        // Fail fast on a weak secret rather than run with it.
        requireStrong("ledgerforge.admin-token", adminToken);
        requireStrong("ledgerforge.metrics-token", metricsToken);
        if (adminToken != null && !adminToken.isBlank() && adminToken.equals(metricsToken)) {
            throw new IllegalArgumentException("ledgerforge.metrics-token must differ from the admin token");
        }
    }

    private static void requireStrong(String name, String token) {
        if (token != null && !token.isBlank() && token.length() < MIN_ADMIN_TOKEN_LENGTH) {
            throw new IllegalArgumentException(name + " must be at least " + MIN_ADMIN_TOKEN_LENGTH + " characters");
        }
    }

    public boolean adminEnabled() {
        return adminToken != null && !adminToken.isBlank();
    }

    public enum Store { MEMORY, REDIS }

    /**
     * @param store where buckets live: MEMORY (per instance) or REDIS (shared by every instance)
     */
    public record RateLimit(@DefaultValue("100") long capacity, @DefaultValue("50") double refillPerSecond,
                            @DefaultValue("memory") Store store) {
    }
}

package io.ledgerforge.webhook;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

/**
 * @param encryptionKey          base64 AES-256 key that encrypts endpoint signing secrets at rest. Empty = a
 *                               temporary key per start (development only: stored secrets become unreadable
 *                               after a restart)
 * @param requireEncryptionKey   refuse to start without one (prod profile)
 * @param allowInsecureTargets   allow http://, private and loopback addresses and any port. Development and
 *                               tests only: in production this would let a tenant aim requests at internal
 *                               services (server-side request forgery)
 * @param pollInterval           how often the dispatcher looks for due deliveries
 * @param retryDelays            wait before each retry; attempts = retries + 1. Jitter of up to 10% is added
 * @param disableAfterFailedDeliveries  turn an endpoint off after this many deliveries in a row give up
 * @param maxEndpointsPerTenant  registration limit
 * @param maxConcurrentDeliveries sends in flight per instance
 */
@ConfigurationProperties("ledgerforge.webhooks")
public record WebhookProperties(
        String encryptionKey,
        boolean requireEncryptionKey,
        boolean allowInsecureTargets,
        @DefaultValue("true") boolean dispatcherEnabled,
        @DefaultValue("1s") Duration pollInterval,
        @DefaultValue({"30s", "2m", "10m", "1h", "3h", "6h", "12h"}) List<Duration> retryDelays,
        @DefaultValue("3s") Duration connectTimeout,
        @DefaultValue("10s") Duration requestTimeout,
        @DefaultValue("5") int disableAfterFailedDeliveries,
        @DefaultValue("10") int maxEndpointsPerTenant,
        @DefaultValue("16") int maxConcurrentDeliveries) {

    public WebhookProperties {
        if (retryDelays.stream().anyMatch(d -> d.isNegative() || d.isZero())) {
            throw new IllegalArgumentException("ledgerforge.webhooks.retry-delays must all be positive");
        }
        if (maxConcurrentDeliveries < 1 || maxEndpointsPerTenant < 1 || disableAfterFailedDeliveries < 1) {
            throw new IllegalArgumentException("ledgerforge.webhooks limits must be at least 1");
        }
    }

    public int maxAttempts() {
        return retryDelays.size() + 1;
    }

    /** How long a claimed delivery is reserved: comfortably longer than one send can take. */
    public Duration lease() {
        return connectTimeout.plus(requestTimeout).multipliedBy(2).plusSeconds(30);
    }
}

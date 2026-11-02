package io.ledgerforge.webhook;

import io.ledgerforge.audit.AuditService;
import io.ledgerforge.common.LedgerMetrics;
import io.ledgerforge.tenant.TenantRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** The dispatcher's database side: short transactions around (never during) the HTTP call. */
@Component
public class WebhookDeliveryRecorder {

    /** Everything needed to send one delivery, read in one transaction. */
    public record Attempt(UUID deliveryId, UUID tenantId, UUID eventId, String url, String secret, String body,
                          int attemptNumber) {
    }

    private final WebhookDeliveryRepository deliveries;
    private final WebhookEndpointRepository endpoints;
    private final WebhookEventRepository events;
    private final SecretCipher cipher;
    private final WebhookProperties properties;
    private final TenantRepository tenants;
    private final AuditService audit;
    private final LedgerMetrics metrics;

    public WebhookDeliveryRecorder(WebhookDeliveryRepository deliveries, WebhookEndpointRepository endpoints,
                                   WebhookEventRepository events, SecretCipher cipher, WebhookProperties properties,
                                   TenantRepository tenants, AuditService audit, LedgerMetrics metrics) {
        this.deliveries = deliveries;
        this.endpoints = endpoints;
        this.events = events;
        this.cipher = cipher;
        this.properties = properties;
        this.tenants = tenants;
        this.audit = audit;
        this.metrics = metrics;
    }

    @Transactional
    public boolean claim(UUID deliveryId, Instant seen, Instant leaseUntil) {
        return deliveries.claim(deliveryId, seen, leaseUntil) == 1;
    }

    /**
     * Loads a claimed delivery. Empty if it no longer needs sending; a delivery whose endpoint has been
     * disabled or deleted meanwhile is cancelled instead.
     */
    @Transactional
    public Optional<Attempt> prepare(UUID deliveryId) {
        WebhookDelivery delivery = deliveries.findById(deliveryId).orElse(null);
        if (delivery == null || delivery.getStatus() != WebhookDelivery.Status.PENDING) {
            return Optional.empty();
        }
        WebhookEndpoint endpoint = endpoints.findById(delivery.getEndpointId()).orElse(null);
        if (endpoint == null || !endpoint.isActive() || !endpoint.getTenantId().equals(delivery.getTenantId())) {
            delivery.cancel("endpoint_disabled");
            metrics.webhookDelivery("cancelled");
            return Optional.empty();
        }
        WebhookEvent event = events.findById(delivery.getEventId()).orElseThrow();
        String secret = cipher.decrypt(endpoint.getId(), endpoint.getSecretCiphertext());
        return Optional.of(new Attempt(delivery.getId(), delivery.getTenantId(), event.getId(), endpoint.getUrl(),
                secret, event.getPayload(), delivery.getAttempts() + 1));
    }

    /**
     * Records one attempt. 2xx succeeds; anything else is retried on the configured schedule, and gives
     * up after the last retry. Several give-ups in a row disable the endpoint.
     *
     * @param statusCode the HTTP status, or null when no response came back
     * @param error      a short, fixed reason for failures (never the response body)
     */
    @Transactional
    public void record(UUID deliveryId, Integer statusCode, String error) {
        WebhookDelivery delivery = deliveries.findById(deliveryId).orElse(null);
        if (delivery == null || delivery.getStatus() != WebhookDelivery.Status.PENDING) {
            return; // cancelled while in flight
        }
        boolean success = statusCode != null && statusCode >= 200 && statusCode < 300;
        boolean givingUp = !success && delivery.getAttempts() + 1 >= properties.maxAttempts();
        if (givingUp) {
            // May disable the endpoint and write an audit event: take the tenant lock first, as every
            // other writer does, before touching any row.
            tenants.lockById(delivery.getTenantId()).orElseThrow();
        }
        Instant now = Instant.now();
        delivery.recordAttempt(now, statusCode, success ? null : error);
        WebhookEndpoint endpoint = endpoints.findById(delivery.getEndpointId()).orElseThrow();

        if (success) {
            delivery.succeed();
            endpoint.deliverySucceeded();
            metrics.webhookDelivery("succeeded");
        } else if (!givingUp) {
            delivery.retryAt(now.plus(withJitter(properties.retryDelays().get(delivery.getAttempts() - 1))));
            metrics.webhookDelivery("retrying");
        } else {
            delivery.fail();
            metrics.webhookDelivery("failed");
            int inRow = endpoint.deliveryFailed();
            if (endpoint.isEnabled() && inRow >= properties.disableAfterFailedDeliveries()) {
                endpoint.disable();
                audit.append(delivery.getTenantId(), "WEBHOOK_ENDPOINT_DISABLED", "endpointId=" + endpoint.getId()
                        + ";reason=" + inRow + " deliveries failed in a row");
            }
        }
    }

    private static Duration withJitter(Duration delay) {
        long jitterMillis = (long) (delay.toMillis() * 0.1 * ThreadLocalRandom.current().nextDouble());
        return delay.plusMillis(jitterMillis);
    }
}

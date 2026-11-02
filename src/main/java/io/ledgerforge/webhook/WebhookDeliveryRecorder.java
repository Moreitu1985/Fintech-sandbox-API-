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

/**
 * The dispatcher's database side: short transactions around (never during) the HTTP call.
 *
 * <p>Every write is fenced by the lease: {@code leaseUntil} is the next-attempt time the claim set, and
 * only the instance whose lease is still on the row may record. If a slow instance's lease ran out and
 * another instance took the delivery over, the slow one's result is dropped. The row's version, bumped by
 * every claim and cancellation, catches a takeover that happens between the check and the commit.
 */
@Component
public class WebhookDeliveryRecorder {

    /** Everything needed to send one delivery, read in one transaction. */
    public record Attempt(UUID deliveryId, UUID tenantId, UUID eventId, String url, String secret, String body,
                          int attemptNumber) {
        @Override
        public String toString() {
            // Never print the signing secret, whatever logs this later.
            return "Attempt[deliveryId=" + deliveryId + ", eventId=" + eventId + ", attempt=" + attemptNumber + "]";
        }
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
     * Loads a claimed delivery. Empty if this caller no longer holds it, or it no longer needs sending; a
     * delivery whose endpoint has been disabled or deleted meanwhile is cancelled instead.
     */
    @Transactional
    public Optional<Attempt> prepare(UUID deliveryId, Instant leaseUntil) {
        WebhookDelivery delivery = deliveries.findById(deliveryId).orElse(null);
        if (delivery == null || !delivery.leasedUntil(leaseUntil)) {
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
     * @param leaseUntil the caller's claim; nothing is recorded if another instance has taken over since
     * @param statusCode the HTTP status, or null when no response came back
     * @param error      a short, fixed reason for failures (never the response body)
     */
    @Transactional
    public void record(UUID deliveryId, Instant leaseUntil, Integer statusCode, String error) {
        boolean success = statusCode != null && statusCode >= 200 && statusCode < 300;
        if (!success) {
            // A give-up may disable the endpoint and write an audit event. Take the tenant lock before
            // loading or changing any row, as every other writer does, so the decision uses current state.
            var progress = deliveries.progressOf(deliveryId).orElse(null);
            if (progress == null) {
                return;
            }
            if (progress.getAttempts() + 1 >= properties.maxAttempts()) {
                tenants.lockById(progress.getTenantId()).orElseThrow();
            }
        }
        // First load of the entity in this transaction, after any lock: current state.
        record(deliveries.findById(deliveryId).orElse(null), leaseUntil, success, statusCode, error);
    }

    private void record(WebhookDelivery delivery, Instant leaseUntil, boolean success, Integer statusCode,
                        String error) {
        if (delivery == null || !delivery.leasedUntil(leaseUntil)) {
            return; // cancelled, deleted or taken over by another instance while in flight
        }
        Instant now = Instant.now();
        delivery.recordAttempt(now, statusCode, success ? null : error);
        boolean givingUp = !success && delivery.getAttempts() >= properties.maxAttempts();

        if (success) {
            delivery.succeed();
            endpoints.resetFailureStreak(delivery.getEndpointId());
            metrics.webhookDelivery("succeeded");
        } else if (!givingUp) {
            delivery.retryAt(now.plus(withJitter(properties.retryDelays().get(delivery.getAttempts() - 1))));
            metrics.webhookDelivery("retrying");
        } else {
            delivery.fail();
            metrics.webhookDelivery("failed");
            WebhookEndpoint endpoint = endpoints.findById(delivery.getEndpointId()).orElseThrow();
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

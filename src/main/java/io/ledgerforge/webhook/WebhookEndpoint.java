package io.ledgerforge.webhook;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** A tenant's HTTPS endpoint and the event types it subscribes to. The id is assigned in code, because
 *  the encrypted secret is bound to it. */
@Entity
@Table(name = "webhook_endpoints")
public class WebhookEndpoint {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false, length = 2048)
    private String url;

    @Column(name = "event_types", nullable = false, length = 400)
    private String eventTypes;

    @Column(length = 140)
    private String description;

    @Column(name = "secret_ciphertext", nullable = false, length = 200)
    private String secretCiphertext;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "failed_deliveries_in_row", nullable = false)
    private int failedDeliveriesInRow;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "disabled_at")
    private Instant disabledAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected WebhookEndpoint() {
    }

    WebhookEndpoint(UUID id, UUID tenantId, String url, List<String> eventTypes, String description,
                    String secretCiphertext) {
        this.id = id;
        this.tenantId = tenantId;
        this.url = url;
        this.eventTypes = String.join(",", eventTypes);
        this.description = description;
        this.secretCiphertext = secretCiphertext;
        this.enabled = true;
        this.failedDeliveriesInRow = 0;
        this.createdAt = Instant.now();
    }

    boolean subscribesTo(String eventType) {
        return eventTypeList().contains(eventType);
    }

    boolean isActive() {
        return enabled && deletedAt == null;
    }

    void rotateSecret(String newCiphertext) {
        this.secretCiphertext = newCiphertext;
    }

    void enable() {
        this.enabled = true;
        this.disabledAt = null;
        this.failedDeliveriesInRow = 0;
    }

    void disable() {
        this.enabled = false;
        this.disabledAt = Instant.now();
    }

    void delete() {
        this.enabled = false;
        this.deletedAt = Instant.now();
    }

    void deliverySucceeded() {
        this.failedDeliveriesInRow = 0;
    }

    /** @return the new count of deliveries that exhausted their retries in a row */
    int deliveryFailed() {
        return ++this.failedDeliveriesInRow;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getUrl() {
        return url;
    }

    public List<String> eventTypeList() {
        return Arrays.stream(eventTypes.split(",")).filter(s -> !s.isBlank()).toList();
    }

    public String getDescription() {
        return description;
    }

    String getSecretCiphertext() {
        return secretCiphertext;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getFailedDeliveriesInRow() {
        return failedDeliveriesInRow;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getDisabledAt() {
        return disabledAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }
}

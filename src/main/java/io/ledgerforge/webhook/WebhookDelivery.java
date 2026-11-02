package io.ledgerforge.webhook;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** One event to one endpoint: its state, attempts and the result of the last attempt. */
@Entity
@Table(name = "webhook_deliveries")
public class WebhookDelivery {

    public enum Status { PENDING, SUCCEEDED, FAILED, CANCELLED }

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "endpoint_id", nullable = false, updatable = false)
    private UUID endpointId;

    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;

    @Column(name = "last_status_code")
    private Integer lastStatusCode;

    @Column(name = "last_error", length = 200)
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected WebhookDelivery() {
    }

    WebhookDelivery(UUID tenantId, UUID endpointId, UUID eventId, Instant now) {
        this.id = UUID.randomUUID();
        this.tenantId = tenantId;
        this.endpointId = endpointId;
        this.eventId = eventId;
        this.status = Status.PENDING;
        this.attempts = 0;
        this.nextAttemptAt = now;
        this.createdAt = now;
    }

    void recordAttempt(Instant at, Integer statusCode, String error) {
        this.attempts++;
        this.lastAttemptAt = at;
        this.lastStatusCode = statusCode;
        this.lastError = error == null ? null : error.substring(0, Math.min(error.length(), 200));
    }

    void succeed() {
        this.status = Status.SUCCEEDED;
    }

    void retryAt(Instant when) {
        this.status = Status.PENDING;
        this.nextAttemptAt = when;
    }

    void fail() {
        this.status = Status.FAILED;
    }

    void cancel(String reason) {
        this.status = Status.CANCELLED;
        this.lastError = reason;
    }

    /** A tenant asked for another go at a delivery that gave up. */
    void requeue(Instant now) {
        this.status = Status.PENDING;
        this.attempts = 0;
        this.nextAttemptAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getEndpointId() {
        return endpointId;
    }

    public UUID getEventId() {
        return eventId;
    }

    public Status getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public Instant getLastAttemptAt() {
        return lastAttemptAt;
    }

    public Integer getLastStatusCode() {
        return lastStatusCode;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

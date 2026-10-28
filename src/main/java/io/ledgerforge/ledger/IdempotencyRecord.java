package io.ledgerforge.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Maps a client's Idempotency-Key to the transfer it produced, plus a hash of the original request. */
@Entity
@Table(name = "idempotency_keys")
public class IdempotencyRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "idem_key", nullable = false, updatable = false, length = 64)
    private String idemKey;

    @Column(name = "request_hash", nullable = false, updatable = false, length = 64)
    private String requestHash;

    @Column(name = "transaction_id", nullable = false, updatable = false)
    private UUID transactionId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected IdempotencyRecord() {
    }

    public IdempotencyRecord(UUID tenantId, String idemKey, String requestHash, UUID transactionId) {
        this.tenantId = tenantId;
        this.idemKey = idemKey;
        this.requestHash = requestHash;
        this.transactionId = transactionId;
        this.createdAt = Instant.now();
    }

    public String getRequestHash() {
        return requestHash;
    }

    public UUID getTransactionId() {
        return transactionId;
    }
}

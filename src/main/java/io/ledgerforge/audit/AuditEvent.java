package io.ledgerforge.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "audit_events")
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "chain_index", nullable = false, updatable = false)
    private long chainIndex;

    @Column(name = "event_type", nullable = false, updatable = false, length = 40)
    private String eventType;

    @Column(nullable = false, updatable = false, length = 4000)
    private String payload;

    @Column(name = "created_at_ms", nullable = false, updatable = false)
    private long createdAtMillis;

    @Column(name = "prev_hash", nullable = false, updatable = false, length = 64)
    private String prevHash;

    @Column(nullable = false, updatable = false, length = 64)
    private String hash;

    protected AuditEvent() {
    }

    public AuditEvent(UUID tenantId, long chainIndex, String eventType, String payload, long createdAtMillis,
                      String prevHash, String hash) {
        this.tenantId = tenantId;
        this.chainIndex = chainIndex;
        this.eventType = eventType;
        this.payload = payload;
        this.createdAtMillis = createdAtMillis;
        this.prevHash = prevHash;
        this.hash = hash;
    }

    public AuditChain.Link toLink() {
        return new AuditChain.Link(chainIndex, eventType, payload, createdAtMillis, prevHash, hash);
    }

    public Long getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public long getChainIndex() {
        return chainIndex;
    }

    public String getEventType() {
        return eventType;
    }

    public String getPayload() {
        return payload;
    }

    public long getCreatedAtMillis() {
        return createdAtMillis;
    }

    public String getPrevHash() {
        return prevHash;
    }

    public String getHash() {
        return hash;
    }
}

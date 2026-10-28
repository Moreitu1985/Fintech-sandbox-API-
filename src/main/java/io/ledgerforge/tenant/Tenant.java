package io.ledgerforge.tenant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** A fintech customer of the platform. All data is partitioned by tenant. */
@Entity
@Table(name = "tenants")
public class Tenant {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "audit_head_index")
    private Long auditHeadIndex;

    @Column(name = "audit_head_hash", length = 64)
    private String auditHeadHash;

    protected Tenant() {
    }

    public void advanceAuditHead(long index, String hash) {
        this.auditHeadIndex = index;
        this.auditHeadHash = hash;
    }

    public Long getAuditHeadIndex() {
        return auditHeadIndex;
    }

    public String getAuditHeadHash() {
        return auditHeadHash;
    }

    public Tenant(String name) {
        this.name = name;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

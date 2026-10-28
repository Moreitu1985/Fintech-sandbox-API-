package io.ledgerforge.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "accounts")
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, updatable = false, length = 3)
    private String currency;

    /** Only funding/settlement accounts may go negative; customer accounts never can. */
    @Column(name = "allow_negative", nullable = false, updatable = false)
    private boolean allowNegative;

    /** Cached sum of this account's postings, in minor units. Verified by the integrity check. */
    @Column(nullable = false)
    private long balance;

    @Version
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Account() {
    }

    public Account(UUID tenantId, String name, String currency, boolean allowNegative) {
        this.tenantId = tenantId;
        this.name = name;
        this.currency = currency;
        this.allowNegative = allowNegative;
        this.balance = 0;
        this.createdAt = Instant.now();
    }

    /** Overflow throws rather than silently wrapping around. */
    void applyPosting(long amount) {
        this.balance = Math.addExact(this.balance, amount);
    }

    public boolean canCover(long amount) {
        return allowNegative || balance >= amount;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getName() {
        return name;
    }

    public String getCurrency() {
        return currency;
    }

    public boolean isAllowNegative() {
        return allowNegative;
    }

    public long getBalance() {
        return balance;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

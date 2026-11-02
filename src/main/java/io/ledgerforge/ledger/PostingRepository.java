package io.ledgerforge.ledger;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface PostingRepository extends JpaRepository<Posting, UUID> {

    Page<Posting> findByAccountIdAndTenantId(UUID accountId, UUID tenantId, Pageable pageable);

    /** Double entry means this is zero for every tenant, always. */
    @Query("select coalesce(sum(p.amount), 0L) from Posting p where p.tenantId = :tenantId")
    long sumByTenant(UUID tenantId);

    // Behaviour features for fraud scoring. Outgoing money is a negative posting on the source account.

    @Query("""
            select count(p) from Posting p
            where p.accountId = :accountId and p.amount < 0 and p.createdAt >= :since
            """)
    long countOutgoingSince(UUID accountId, Instant since);

    /** Total sent since the given time, as a positive number of minor units. */
    @Query("""
            select coalesce(-sum(p.amount), 0L) from Posting p
            where p.accountId = :accountId and p.amount < 0 and p.createdAt >= :since
            """)
    long sumOutgoingSince(UUID accountId, Instant since);

    @Query("select count(p) from Posting p where p.accountId = :accountId and p.amount < 0")
    long countOutgoing(UUID accountId);

    /**
     * Per-account activity since a time: [accountId, postingCount, sentMinor, receivedMinor].
     * Used by the assistant's transfer_summary tool.
     */
    @Query("""
            select p.accountId, count(p),
                   coalesce(sum(case when p.amount < 0 then -p.amount else 0L end), 0L),
                   coalesce(sum(case when p.amount > 0 then p.amount else 0L end), 0L)
            from Posting p
            where p.tenantId = :tenantId and p.createdAt >= :since
            group by p.accountId
            """)
    List<Object[]> summarizeByAccount(UUID tenantId, Instant since);
}

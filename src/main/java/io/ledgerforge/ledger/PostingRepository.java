package io.ledgerforge.ledger;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.UUID;

public interface PostingRepository extends JpaRepository<Posting, UUID> {

    Page<Posting> findByAccountIdAndTenantId(UUID accountId, UUID tenantId, Pageable pageable);

    /** Double entry means this is zero for every tenant, always. */
    @Query("select coalesce(sum(p.amount), 0L) from Posting p where p.tenantId = :tenantId")
    long sumByTenant(UUID tenantId);
}

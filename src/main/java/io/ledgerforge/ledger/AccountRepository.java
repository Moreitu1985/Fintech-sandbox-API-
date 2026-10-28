package io.ledgerforge.ledger;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccountRepository extends JpaRepository<Account, UUID> {

    Optional<Account> findByIdAndTenantId(UUID id, UUID tenantId);

    Page<Account> findByTenantId(UUID tenantId, Pageable pageable);

    long countByTenantId(UUID tenantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.id = :id and a.tenantId = :tenantId")
    Optional<Account> lockByIdAndTenantId(UUID id, UUID tenantId);

    /** Accounts whose cached balance disagrees with their postings. Must always be empty. */
    @Query("""
            select a.id from Account a
            where a.tenantId = :tenantId
              and a.balance <> (select coalesce(sum(p.amount), 0L) from Posting p where p.accountId = a.id)
            """)
    List<UUID> findBalanceMismatches(UUID tenantId);
}

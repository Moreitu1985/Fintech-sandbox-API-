package io.ledgerforge.ledger;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface LedgerTransactionRepository extends JpaRepository<LedgerTransaction, UUID> {

    Optional<LedgerTransaction> findByIdAndTenantId(UUID id, UUID tenantId);

    boolean existsByTenantIdAndFromAccountIdAndToAccountId(UUID tenantId, UUID fromAccountId, UUID toAccountId);
}

package io.ledgerforge.audit;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {

    Optional<AuditEvent> findTopByTenantIdOrderByChainIndexDesc(UUID tenantId);

    List<AuditEvent> findByTenantIdOrderByChainIndexAsc(UUID tenantId);

    /** Newest first, at most 50: e.g. FRAUD_REVIEW / FRAUD_BLOCK events for the assistant. */
    List<AuditEvent> findTop50ByTenantIdAndEventTypeInAndCreatedAtMillisGreaterThanEqualOrderByChainIndexDesc(
            UUID tenantId, Collection<String> eventTypes, long sinceMillis);
}

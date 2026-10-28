package io.ledgerforge.audit;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {

    Optional<AuditEvent> findTopByTenantIdOrderByChainIndexDesc(UUID tenantId);

    List<AuditEvent> findByTenantIdOrderByChainIndexAsc(UUID tenantId);
}

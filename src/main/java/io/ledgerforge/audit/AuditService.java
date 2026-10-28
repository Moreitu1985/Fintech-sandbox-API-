package io.ledgerforge.audit;

import io.ledgerforge.common.error.ApiException;
import io.ledgerforge.tenant.Tenant;
import io.ledgerforge.tenant.TenantRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class AuditService {

    private final AuditEventRepository events;
    private final TenantRepository tenants;

    public AuditService(AuditEventRepository events, TenantRepository tenants) {
        this.events = events;
        this.tenants = tenants;
    }

    /**
     * Appends an event in the caller's transaction, so the audit record commits or rolls back
     * together with the change it describes. The tenant row is locked (callers normally hold it
     * already) so appends queue up instead of forking the chain; the unique (tenant, chain_index)
     * constraint is the backstop. The tenant row also records the chain head.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public AuditEvent append(UUID tenantId, String eventType, String payload) {
        Tenant tenant = tenants.lockById(tenantId).orElseThrow(() -> ApiException.notFound("Tenant"));
        var last = events.findTopByTenantIdOrderByChainIndexDesc(tenantId);
        long index = last.map(e -> e.getChainIndex() + 1).orElse(0L);
        String prevHash = last.map(AuditEvent::getHash).orElse(AuditChain.GENESIS);
        long now = Instant.now().toEpochMilli();
        String hash = AuditChain.computeHash(prevHash, tenantId, index, eventType, payload, now);
        AuditEvent saved = events.save(new AuditEvent(tenantId, index, eventType, payload, now, prevHash, hash));
        tenant.advanceAuditHead(index, hash);
        return saved;
    }

    @Transactional(readOnly = true)
    public AuditChain.Verification verify(UUID tenantId) {
        Tenant tenant = tenants.findById(tenantId).orElseThrow(() -> ApiException.notFound("Tenant"));
        var links = events.findByTenantIdOrderByChainIndexAsc(tenantId).stream().map(AuditEvent::toLink).toList();
        return AuditChain.verify(tenantId, links, tenant.getAuditHeadIndex(), tenant.getAuditHeadHash());
    }
}

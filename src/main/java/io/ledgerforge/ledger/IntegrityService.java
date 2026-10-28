package io.ledgerforge.ledger;

import io.ledgerforge.audit.AuditService;
import io.ledgerforge.ledger.dto.LedgerDtos.IntegrityReport;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/** Proves three invariants on demand: books balance, balances match history, audit chain is intact. */
@Service
public class IntegrityService {

    private final AccountRepository accounts;
    private final PostingRepository postings;
    private final AuditService audit;

    public IntegrityService(AccountRepository accounts, PostingRepository postings, AuditService audit) {
        this.accounts = accounts;
        this.postings = postings;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public IntegrityReport check(UUID tenantId) {
        long sum = postings.sumByTenant(tenantId);
        var mismatches = accounts.findBalanceMismatches(tenantId);
        long accountsChecked = accounts.countByTenantId(tenantId);
        var chain = audit.verify(tenantId);
        boolean healthy = sum == 0 && mismatches.isEmpty() && chain.valid();
        return new IntegrityReport(healthy, sum, accountsChecked, mismatches, chain.valid(), chain.eventsChecked(),
                chain.firstBrokenIndex(), Instant.now());
    }
}

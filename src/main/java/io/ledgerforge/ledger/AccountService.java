package io.ledgerforge.ledger;

import io.ledgerforge.audit.AuditService;
import io.ledgerforge.common.error.ApiException;
import io.ledgerforge.ledger.dto.LedgerDtos.CreateAccountRequest;
import io.ledgerforge.tenant.TenantRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class AccountService {

    public static final int MAX_PAGE_SIZE = 100;

    private final AccountRepository accounts;
    private final PostingRepository postings;
    private final AuditService audit;
    private final TenantRepository tenants;

    public AccountService(AccountRepository accounts, PostingRepository postings, AuditService audit,
                          TenantRepository tenants) {
        this.tenants = tenants;
        this.accounts = accounts;
        this.postings = postings;
        this.audit = audit;
    }

    @Transactional
    public Account open(UUID tenantId, CreateAccountRequest request) {
        tenants.lockById(tenantId).orElseThrow(() -> ApiException.notFound("Tenant")); // tenant first, always
        boolean allowNegative = Boolean.TRUE.equals(request.allowNegative());
        Account account = accounts.save(new Account(tenantId, request.name().strip(), request.currency(), allowNegative));
        audit.append(tenantId, "ACCOUNT_OPENED", "accountId=" + account.getId()
                + ";currency=" + account.getCurrency() + ";allowNegative=" + allowNegative);
        return account;
    }

    @Transactional(readOnly = true)
    public Account get(UUID tenantId, UUID accountId) {
        return accounts.findByIdAndTenantId(accountId, tenantId).orElseThrow(() -> ApiException.notFound("Account"));
    }

    @Transactional(readOnly = true)
    public Page<Account> list(UUID tenantId, int page, int size) {
        return accounts.findByTenantId(tenantId, pageRequest(page, size, Sort.by("createdAt", "id")));
    }

    @Transactional(readOnly = true)
    public Page<Posting> entries(UUID tenantId, UUID accountId, int page, int size) {
        get(tenantId, accountId); // 404 if the account is not this tenant's
        return postings.findByAccountIdAndTenantId(accountId, tenantId,
                pageRequest(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))));
    }

    static PageRequest pageRequest(int page, int size, Sort sort) {
        return PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE), sort);
    }
}

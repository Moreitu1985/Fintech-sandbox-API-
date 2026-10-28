package io.ledgerforge.ledger;

import io.ledgerforge.audit.AuditService;
import io.ledgerforge.common.Hashing;
import io.ledgerforge.common.error.ApiException;
import io.ledgerforge.ledger.dto.LedgerDtos.TransferRequest;
import io.ledgerforge.ledger.dto.LedgerDtos.TransferResponse;
import io.ledgerforge.plugin.TransferContext;
import io.ledgerforge.plugin.TransferPlugin;
import io.ledgerforge.tenant.TenantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The money path. One database transaction covers: idempotency check, account locks, plugin checks,
 * balance check, both postings, the idempotency record and the audit event. All of it commits, or none of it.
 */
@Service
public class TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("^[A-Za-z0-9_-]{8,64}$");

    private final AccountRepository accounts;
    private final LedgerTransactionRepository transactions;
    private final PostingRepository postings;
    private final IdempotencyRecordRepository idempotency;
    private final AuditService audit;
    private final List<TransferPlugin> plugins;
    private final TenantRepository tenants;

    public TransferService(AccountRepository accounts, LedgerTransactionRepository transactions,
                           PostingRepository postings, IdempotencyRecordRepository idempotency,
                           AuditService audit, List<TransferPlugin> plugins, TenantRepository tenants) {
        this.tenants = tenants;
        this.accounts = accounts;
        this.transactions = transactions;
        this.postings = postings;
        this.idempotency = idempotency;
        this.audit = audit;
        this.plugins = plugins;
    }

    public record Result(TransferResponse transfer, boolean replayed) {
    }

    @Transactional
    public Result transfer(UUID tenantId, String idempotencyKey, TransferRequest request) {
        if (idempotencyKey == null || !IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
            throw ApiException.badRequest("INVALID_IDEMPOTENCY_KEY",
                    "Idempotency-Key header must be 8-64 characters of letters, digits, '-' or '_'.");
        }
        if (request.fromAccountId().equals(request.toAccountId())) {
            throw ApiException.unprocessable("SAME_ACCOUNT", "Source and destination accounts must differ.");
        }
        String requestHash = Hashing.sha256Hex(canonical(request));

        // Lock order for every write path: tenant first, then accounts in ascending id order.
        // The tenant lock also serialises same-key requests, so the idempotency check below cannot race.
        tenants.lockById(tenantId).orElseThrow(() -> ApiException.notFound("Tenant"));

        Optional<Result> replay = replayIfSeen(tenantId, idempotencyKey, requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }

        UUID firstId = request.fromAccountId().compareTo(request.toAccountId()) < 0
                ? request.fromAccountId() : request.toAccountId();
        UUID secondId = firstId.equals(request.fromAccountId()) ? request.toAccountId() : request.fromAccountId();
        Account first = lock(tenantId, firstId);
        Account second = lock(tenantId, secondId);
        Account from = first.getId().equals(request.fromAccountId()) ? first : second;
        Account to = from == first ? second : first;

        long amount = request.amount();
        if (!from.getCurrency().equals(request.currency()) || !to.getCurrency().equals(request.currency())) {
            throw ApiException.unprocessable("CURRENCY_MISMATCH",
                    "Both accounts must be in " + request.currency() + ". Currency conversion is not supported.");
        }

        TransferContext context = new TransferContext(tenantId, from.getId(), to.getId(), amount,
                request.currency(), request.reference(), null);
        for (TransferPlugin plugin : plugins) {
            plugin.beforeTransfer(context);
        }

        if (!from.canCover(amount)) {
            throw ApiException.unprocessable("INSUFFICIENT_FUNDS", "The source account has insufficient funds.");
        }

        LedgerTransaction txn = transactions.save(new LedgerTransaction(tenantId, from.getId(), to.getId(), amount,
                request.currency(), request.reference(), request.description()));
        Instant postedAt = txn.getCreatedAt();
        postings.save(new Posting(tenantId, txn.getId(), from.getId(), -amount, postedAt));
        postings.save(new Posting(tenantId, txn.getId(), to.getId(), amount, postedAt));
        from.applyPosting(-amount);
        to.applyPosting(amount);

        idempotency.save(new IdempotencyRecord(tenantId, idempotencyKey, requestHash, txn.getId()));
        audit.append(tenantId, "TRANSFER_POSTED", "transactionId=" + txn.getId() + ";from=" + from.getId()
                + ";to=" + to.getId() + ";amount=" + amount + ";currency=" + request.currency());

        runAfterCommit(context.withTransactionId(txn.getId()));
        return new Result(TransferResponse.from(txn), false);
    }

    @Transactional(readOnly = true)
    public TransferResponse get(UUID tenantId, UUID transactionId) {
        return transactions.findByIdAndTenantId(transactionId, tenantId)
                .map(TransferResponse::from)
                .orElseThrow(() -> ApiException.notFound("Transfer"));
    }

    private Optional<Result> replayIfSeen(UUID tenantId, String key, String requestHash) {
        return idempotency.findByTenantIdAndIdemKey(tenantId, key).map(record -> {
            if (!record.getRequestHash().equals(requestHash)) {
                throw ApiException.unprocessable("IDEMPOTENCY_KEY_REUSED",
                        "This Idempotency-Key was already used with a different request.");
            }
            LedgerTransaction original = transactions.findById(record.getTransactionId())
                    .orElseThrow(() -> new IllegalStateException("Idempotency record points to a missing transaction"));
            return new Result(TransferResponse.from(original), true);
        });
    }

    private Account lock(UUID tenantId, UUID accountId) {
        return accounts.lockByIdAndTenantId(accountId, tenantId).orElseThrow(() -> ApiException.notFound("Account"));
    }

    private void runAfterCommit(TransferContext context) {
        if (plugins.isEmpty() || !TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                for (TransferPlugin plugin : plugins) {
                    try {
                        plugin.afterCommit(context);
                    } catch (RuntimeException e) {
                        log.error("Plugin {} failed in afterCommit for transaction {}", plugin.name(),
                                context.transactionId(), e);
                    }
                }
            }
        });
    }

    /** Stable text form of a request, so a retried request hashes identically. */
    static String canonical(TransferRequest r) {
        return String.join("\n", r.fromAccountId().toString(), r.toAccountId().toString(),
                Long.toString(r.amount()), r.currency(), Objects.toString(r.reference(), ""),
                Objects.toString(r.description(), ""));
    }
}

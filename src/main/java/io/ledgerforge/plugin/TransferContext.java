package io.ledgerforge.plugin;

import java.util.UUID;

/** What a plugin sees about a transfer. transactionId is null in beforeTransfer and set in afterCommit. */
public record TransferContext(
        UUID tenantId,
        UUID fromAccountId,
        UUID toAccountId,
        long amount,
        String currency,
        String reference,
        UUID transactionId) {

    public TransferContext withTransactionId(UUID id) {
        return new TransferContext(tenantId, fromAccountId, toAccountId, amount, currency, reference, id);
    }
}

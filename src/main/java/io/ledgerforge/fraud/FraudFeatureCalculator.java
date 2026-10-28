package io.ledgerforge.fraud;

import io.ledgerforge.ledger.Account;
import io.ledgerforge.ledger.LedgerTransactionRepository;
import io.ledgerforge.ledger.PostingRepository;
import io.ledgerforge.plugin.TransferContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/**
 * Builds the model's behaviour features from the ledger's own history, at the moment of the
 * transfer (inside its transaction, with both accounts locked, before any posting is written).
 */
@Component
public class FraudFeatureCalculator {

    static final ZoneId LOCAL_ZONE = ZoneId.of("Africa/Johannesburg");
    private static final double MINOR_UNITS = 100.0; // ZAR, USD, EUR... (2-decimal currencies)

    private final PostingRepository postings;
    private final LedgerTransactionRepository transactions;
    private final Clock clock;

    @Autowired
    public FraudFeatureCalculator(PostingRepository postings, LedgerTransactionRepository transactions) {
        this(postings, transactions, Clock.systemUTC());
    }

    FraudFeatureCalculator(PostingRepository postings, LedgerTransactionRepository transactions, Clock clock) {
        this.postings = postings;
        this.transactions = transactions;
        this.clock = clock;
    }

    public FraudModels.Features compute(TransferContext transfer, Account source) {
        Instant now = clock.instant();
        long amount = transfer.amount();

        long count30d = postings.countOutgoingSince(source.getId(), now.minus(Duration.ofDays(30)));
        long sum30d = postings.sumOutgoingSince(source.getId(), now.minus(Duration.ofDays(30)));
        double ratio = count30d == 0 ? 1.0 : amount / ((double) sum30d / count30d);

        double drain;
        if (source.isAllowNegative()) {
            drain = 0.0; // funding/settlement accounts are expected to go negative
        } else if (source.getBalance() <= 0) {
            drain = 1.0;
        } else {
            drain = Math.min(1.0, (double) amount / source.getBalance());
        }

        return new FraudModels.Features(
                amount / MINOR_UNITS,
                Math.min(ratio, 1e6),
                Math.max(0, Duration.between(source.getCreatedAt(), now).toMinutes() / 60.0),
                postings.countOutgoingSince(source.getId(), now.minus(Duration.ofHours(1))),
                postings.sumOutgoingSince(source.getId(), now.minus(Duration.ofHours(24))) / MINOR_UNITS,
                !transactions.existsByTenantIdAndFromAccountIdAndToAccountId(
                        transfer.tenantId(), transfer.fromAccountId(), transfer.toAccountId()),
                drain,
                now.atZone(LOCAL_ZONE).getHour(),
                postings.countOutgoing(source.getId()));
    }
}

package io.ledgerforge.fraud;

import io.ledgerforge.audit.AuditService;
import io.ledgerforge.common.error.ApiException;
import io.ledgerforge.ledger.Account;
import io.ledgerforge.ledger.AccountRepository;
import io.ledgerforge.plugin.PluginRejectedException;
import io.ledgerforge.plugin.TransferContext;
import io.ledgerforge.plugin.TransferPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Scores every customer transfer with the fraud model before money moves.
 *
 * <ul>
 *   <li>BLOCK: the transfer is refused (422 PLUGIN_REJECTED) with the model's reasons.</li>
 *   <li>REVIEW: the transfer goes ahead and a FRAUD_REVIEW audit event records the score and reasons.</li>
 *   <li>ALLOW: nothing extra happens.</li>
 * </ul>
 *
 * Transfers from funding/settlement accounts (allowNegative) are the platform's own operations and
 * are not scored. If fraud-service is down or slow, {@code fail-open} decides: let the transfer through
 * (logged) or refuse it with 503 FRAUD_CHECK_UNAVAILABLE.
 */
@Component
@Order(100)
@ConditionalOnProperty(prefix = "ledgerforge.plugins.fraud", name = "enabled", havingValue = "true")
public class FraudCheckPlugin implements TransferPlugin {

    private static final Logger log = LoggerFactory.getLogger(FraudCheckPlugin.class);

    private final FraudProperties props;
    private final FraudClient client;
    private final FraudFeatureCalculator features;
    private final AccountRepository accounts;
    private final AuditService audit;

    public FraudCheckPlugin(FraudProperties props, FraudFeatureCalculator features, AccountRepository accounts,
                            AuditService audit) {
        if (props.url() == null || props.url().isBlank()) {
            throw new IllegalStateException("ledgerforge.plugins.fraud.url is required when the fraud plugin is enabled");
        }
        if (props.token() == null || props.token().length() < FraudProperties.MIN_TOKEN_LENGTH) {
            throw new IllegalStateException("ledgerforge.plugins.fraud.token must be at least "
                    + FraudProperties.MIN_TOKEN_LENGTH + " characters");
        }
        this.props = props;
        this.client = new FraudClient(props);
        this.features = features;
        this.accounts = accounts;
        this.audit = audit;
    }

    @Override
    public String name() {
        return "fraud-check";
    }

    @Override
    public String description() {
        return "Scores customer transfers with the ML fraud model; blocks high risk, flags medium risk for review"
                + (props.failOpen() ? " (fail-open)" : " (fail-closed)");
    }

    @Override
    public void beforeTransfer(TransferContext transfer) {
        Account source = accounts.findByIdAndTenantId(transfer.fromAccountId(), transfer.tenantId()).orElse(null);
        if (source == null || source.isAllowNegative()) {
            return;
        }

        FraudModels.ScoreResponse result;
        try {
            result = client.score(new FraudModels.ScoreRequest(transfer.reference(), features.compute(transfer, source)));
        } catch (RuntimeException e) {
            if (props.failOpen()) {
                log.warn("Fraud check unavailable, allowing transfer (fail-open): {}", e.getMessage());
                return;
            }
            log.error("Fraud check unavailable, refusing transfer (fail-closed): {}", e.getMessage());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "FRAUD_CHECK_UNAVAILABLE",
                    "The fraud check is temporarily unavailable. Retry later with the same Idempotency-Key.");
        }

        if (result.blocked()) {
            log.warn("Transfer blocked by fraud model {} (score {}): {}", result.modelVersion(), result.score(),
                    result.reasonSummary());
            throw new PluginRejectedException(name(),
                    "transfer blocked as high fraud risk (" + result.reasonSummary() + ")");
        }
        if (result.needsReview()) {
            audit.append(transfer.tenantId(), "FRAUD_REVIEW", "from=" + transfer.fromAccountId()
                    + ";to=" + transfer.toAccountId() + ";amount=" + transfer.amount()
                    + ";score=" + result.score() + ";model=" + result.modelVersion()
                    + ";reasons=" + result.reasonSummary());
        }
    }
}

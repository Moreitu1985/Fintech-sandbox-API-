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
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

/**
 * Scores every customer transfer with the fraud model before money moves. Runs in
 * {@link #beforeLocking}, so a slow model call never holds the tenant's or the accounts' locks.
 *
 * <ul>
 *   <li>BLOCK: the transfer is refused with a generic 422 (the reasons are not revealed to the caller,
 *       so they can't be used to tune an attack) and a FRAUD_BLOCK audit event is committed on its own,
 *       so the evidence survives the rollback.</li>
 *   <li>REVIEW: the transfer goes ahead and a FRAUD_REVIEW audit event records score and reasons.</li>
 *   <li>ALLOW: nothing extra happens.</li>
 * </ul>
 *
 * Not scored: transfers from funding/settlement accounts (the platform's own operations) and currencies
 * the model was not trained on. Outages (timeouts, connection errors, 5xx) follow {@code fail-open}.
 * Anything else, such as a rejected token or a request the service refuses as invalid, is a
 * misconfiguration and always refuses the transfer, so a broken setup can never silently disable scoring.
 */
@Component
@Order(100)
@ConditionalOnProperty(prefix = "ledgerforge.plugins.fraud", name = "enabled", havingValue = "true")
public class FraudCheckPlugin implements TransferPlugin {

    static final String SCORED_CURRENCY = "ZAR"; // the model is trained on rand amounts
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
        return "Scores " + SCORED_CURRENCY + " customer transfers with the ML fraud model; blocks high risk, "
                + "flags medium risk for review" + (props.failOpen() ? " (fail-open on outage)" : " (fail-closed)");
    }

    @Override
    public void beforeLocking(TransferContext transfer) {
        if (!SCORED_CURRENCY.equals(transfer.currency())) {
            return;
        }
        Account source = accounts.findByIdAndTenantId(transfer.fromAccountId(), transfer.tenantId()).orElse(null);
        if (source == null || source.isAllowNegative()) {
            return; // unknown accounts get their 404 from the core; funding accounts are not scored
        }

        FraudModels.ScoreResponse result;
        try {
            result = client.score(new FraudModels.ScoreRequest(transfer.reference(), features.compute(transfer, source)));
        } catch (ResourceAccessException | HttpServerErrorException outage) {
            if (props.failOpen()) {
                log.warn("Fraud service unavailable, allowing transfer (fail-open): {}", outage.getMessage());
                return;
            }
            log.error("Fraud service unavailable, refusing transfer (fail-closed): {}", outage.getMessage());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "FRAUD_CHECK_UNAVAILABLE",
                    "The fraud check is temporarily unavailable. Retry later with the same Idempotency-Key.");
        } catch (RuntimeException misconfiguration) {
            log.error("Fraud check failed in a way an outage does not explain (token, contract or response "
                    + "problem); refusing transfer", misconfiguration);
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "FRAUD_CHECK_UNAVAILABLE",
                    "The fraud check could not be completed. The platform operator has been alerted.");
        }

        String evidence = "from=" + transfer.fromAccountId() + ";to=" + transfer.toAccountId()
                + ";amount=" + transfer.amount() + ";currency=" + transfer.currency()
                + ";score=" + result.score() + ";model=" + result.modelVersion()
                + ";reasons=" + truncate(result.reasonSummary(), 1500);

        if (result.blocked()) {
            // Committed independently: the transfer's own transaction is about to roll back.
            audit.appendIndependently(transfer.tenantId(), "FRAUD_BLOCK", evidence);
            log.warn("Transfer blocked by fraud model {} (score {}): {}", result.modelVersion(), result.score(),
                    result.reasonSummary());
            throw new PluginRejectedException(name(), "transfer declined by risk checks");
        }
        if (result.needsReview()) {
            audit.append(transfer.tenantId(), "FRAUD_REVIEW", evidence);
        }
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}

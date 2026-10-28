package io.ledgerforge.fraud;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.List;

/** Wire format shared with fraud-service (snake_case JSON, see fraud-service/fraud_service/features.py). */
public final class FraudModels {

    private FraudModels() {
    }

    /** Names are explicit: the automatic snake_case strategy would write "tx_count1h", not "tx_count_1h". */
    public record Features(
            @JsonProperty("amount") double amount,
            @JsonProperty("amount_to_avg_ratio") double amountToAvgRatio,
            @JsonProperty("account_age_hours") double accountAgeHours,
            @JsonProperty("tx_count_1h") long txCount1h,
            @JsonProperty("amount_sum_24h") double amountSum24h,
            @JsonProperty("new_counterparty") boolean newCounterparty,
            @JsonProperty("balance_drain_ratio") double balanceDrainRatio,
            @JsonProperty("hour_of_day") int hourOfDay,
            @JsonProperty("prior_tx_count") long priorTxCount) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ScoreRequest(String transferReference, Features features) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Reason(String feature, double contribution, String message) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ScoreResponse(double score, String decision, List<Reason> reasons, String modelVersion) {

        public boolean blocked() {
            return "BLOCK".equals(decision);
        }

        public boolean needsReview() {
            return "REVIEW".equals(decision);
        }

        public String reasonSummary() {
            return reasons == null || reasons.isEmpty()
                    ? "no reasons given"
                    : String.join("; ", reasons.stream().map(Reason::message).toList());
        }
    }
}

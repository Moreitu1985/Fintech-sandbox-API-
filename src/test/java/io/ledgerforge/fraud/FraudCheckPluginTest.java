package io.ledgerforge.fraud;

import io.ledgerforge.IntegrationTestBase;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class FraudCheckPluginTest extends IntegrationTestBase {

    static final FakeFraudService FRAUD = start();

    static FakeFraudService start() {
        try {
            return new FakeFraudService();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void fraudProperties(DynamicPropertyRegistry registry) {
        registry.add("ledgerforge.plugins.fraud.enabled", () -> "true");
        registry.add("ledgerforge.plugins.fraud.url", FRAUD::url);
        registry.add("ledgerforge.plugins.fraud.token", () -> FakeFraudService.TOKEN);
        registry.add("ledgerforge.plugins.fraud.fail-open", () -> "true");
    }

    @AfterAll
    static void stop() {
        FRAUD.close();
    }

    @Autowired
    private JdbcTemplate jdbc;

    private Tenant tenant;
    private UUID alice;
    private UUID bob;

    @BeforeEach
    void setUp() throws Exception {
        FRAUD.down = false;
        tenant = newTenant();
        UUID treasury = openAccount(tenant, "ZAR", true);
        alice = openAccount(tenant, "ZAR", false);
        bob = openAccount(tenant, "ZAR", false);
        int before = FRAUD.requests.size();
        transfer(tenant, newKey(), treasury, alice, 2_000_000, "ZAR").andExpect(status().isCreated()); // R20,000
        assertThat(FRAUD.requests).as("funding accounts are not scored").hasSize(before);
    }

    @Test
    void sendsBehaviourFeaturesComputedFromTheLedger() throws Exception {
        transfer(tenant, newKey(), alice, bob, 10_000, "ZAR").andExpect(status().isCreated()); // R100
        var first = FRAUD.lastFeatures();
        assertThat(first.get("amount").asDouble()).isEqualTo(100.0);
        assertThat(first.get("new_counterparty").asBoolean()).isTrue();
        assertThat(first.get("prior_tx_count").asLong()).isZero();
        assertThat(first.get("tx_count_1h").asLong()).isZero();
        assertThat(first.get("amount_to_avg_ratio").asDouble()).isEqualTo(1.0);
        assertThat(first.get("balance_drain_ratio").asDouble()).isEqualTo(0.005); // 100 of 20,000
        assertThat(first.get("hour_of_day").asInt()).isBetween(0, 23);

        transfer(tenant, newKey(), alice, bob, 30_000, "ZAR").andExpect(status().isCreated()); // R300
        var second = FRAUD.lastFeatures();
        assertThat(second.get("new_counterparty").asBoolean()).isFalse();
        assertThat(second.get("prior_tx_count").asLong()).isEqualTo(1);
        assertThat(second.get("tx_count_1h").asLong()).isEqualTo(1);
        assertThat(second.get("amount_sum_24h").asDouble()).isEqualTo(100.0);
        assertThat(second.get("amount_to_avg_ratio").asDouble()).isEqualTo(3.0);
    }

    @Test
    void highRiskTransferIsBlockedAndNoMoneyMoves() throws Exception {
        transfer(tenant, newKey(), alice, bob, 950_000, "ZAR") // R9,500
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PLUGIN_REJECTED"))
                .andExpect(jsonPath("$.detail", containsString("Large amount")));
        assertThat(balance(tenant, alice)).isEqualTo(2_000_000);
        assertThat(balance(tenant, bob)).isZero();
        assertThat(integrity(tenant).get("healthy").asBoolean()).isTrue();
    }

    @Test
    void mediumRiskTransferGoesThroughAndIsRecordedForReview() throws Exception {
        transfer(tenant, newKey(), alice, bob, 600_000, "ZAR").andExpect(status().isCreated()); // R6,000
        assertThat(balance(tenant, bob)).isEqualTo(600_000);
        Integer reviews = jdbc.queryForObject(
                "select count(*) from audit_events where tenant_id = ? and event_type = 'FRAUD_REVIEW'",
                Integer.class, tenant.id());
        assertThat(reviews).isEqualTo(1);
        assertThat(integrity(tenant).get("auditChainValid").asBoolean()).isTrue();
    }

    @Test
    void outageFailsOpenWhenConfigured() throws Exception {
        FRAUD.down = true;
        transfer(tenant, newKey(), alice, bob, 10_000, "ZAR").andExpect(status().isCreated());
        assertThat(balance(tenant, bob)).isEqualTo(10_000);
    }

    @Test
    void pluginIsListed() throws Exception {
        mvc.perform(get("/v1/plugins").header("Authorization", tenant.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name", hasItem("fraud-check")));
    }
}

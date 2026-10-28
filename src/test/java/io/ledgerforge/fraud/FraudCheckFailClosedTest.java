package io.ledgerforge.fraud;

import io.ledgerforge.IntegrationTestBase;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** With fail-open=false, an unreachable fraud service stops customer transfers instead of skipping the check. */
class FraudCheckFailClosedTest extends IntegrationTestBase {

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
        registry.add("ledgerforge.plugins.fraud.fail-open", () -> "false");
    }

    @AfterAll
    static void stop() {
        FRAUD.close();
    }

    @Test
    void outageRefusesTransferAndMovesNothing() throws Exception {
        Tenant t = newTenant();
        UUID treasury = openAccount(t, "ZAR", true);
        UUID alice = openAccount(t, "ZAR", false);
        UUID bob = openAccount(t, "ZAR", false);
        transfer(t, newKey(), treasury, alice, 50_000, "ZAR").andExpect(status().isCreated()); // not scored

        FRAUD.down = true;
        transfer(t, newKey(), alice, bob, 10_000, "ZAR")
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("FRAUD_CHECK_UNAVAILABLE"));
        assertThat(balance(t, alice)).isEqualTo(50_000);
        assertThat(balance(t, bob)).isZero();
    }
}

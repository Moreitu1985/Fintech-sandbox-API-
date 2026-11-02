package io.ledgerforge.fraud;

import io.ledgerforge.IntegrationTestBase;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A service pretending to be the fraud service, with a certificate from a CA the ledger doesn't trust,
 * never receives a transfer and can't approve one. (It accepts the ledger's certificate, so the
 * refusal is the ledger's own server verification.)
 */
class FraudMtlsImpostorServerTest extends IntegrationTestBase {

    static final FakeFraudService IMPOSTOR = MtlsSupport.start("rogue-server", "ca");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        MtlsSupport.ledgerProperties(registry, IMPOSTOR, "ledger-client", "ca");
    }

    @AfterAll
    static void stop() {
        IMPOSTOR.close();
    }

    @Test
    void ledgerRefusesToTalkToAnImpostor() throws Exception {
        Tenant t = newTenant();
        UUID treasury = openAccount(t, "ZAR", true);
        UUID alice = openAccount(t, "ZAR", false);
        UUID bob = openAccount(t, "ZAR", false);
        transfer(t, newKey(), treasury, alice, 50_000, "ZAR").andExpect(status().isCreated());

        transfer(t, newKey(), alice, bob, 1_000, "ZAR")
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("FRAUD_CHECK_UNAVAILABLE"));
        assertThat(IMPOSTOR.requests).isEmpty();
        assertThat(balance(t, bob)).isZero();
    }
}

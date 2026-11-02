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
 * A caller whose client certificate isn't from the trusted CA is cut off during the TLS handshake.
 * Fail-open is ON: the handshake failure still refuses the transfer rather than skipping the fraud check.
 */
class FraudMtlsUnknownClientTest extends IntegrationTestBase {

    static final FakeFraudService FRAUD = MtlsSupport.start("fraud-server", "ca");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        MtlsSupport.ledgerProperties(registry, FRAUD, "rogue-client", "ca", true);
    }

    @AfterAll
    static void stop() {
        FRAUD.close();
    }

    @Test
    void fraudServiceRefusesAnUntrustedClientCertificate() throws Exception {
        Tenant t = newTenant();
        UUID treasury = openAccount(t, "ZAR", true);
        UUID alice = openAccount(t, "ZAR", false);
        UUID bob = openAccount(t, "ZAR", false);
        transfer(t, newKey(), treasury, alice, 50_000, "ZAR").andExpect(status().isCreated());

        transfer(t, newKey(), alice, bob, 1_000, "ZAR")
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("FRAUD_CHECK_UNAVAILABLE"));
        assertThat(FRAUD.requests).as("no request got past the handshake").isEmpty();
    }
}

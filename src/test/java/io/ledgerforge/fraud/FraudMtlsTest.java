package io.ledgerforge.fraud;

import io.ledgerforge.IntegrationTestBase;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Happy path: the ledger and the fraud service authenticate each other with certificates from one CA. */
class FraudMtlsTest extends IntegrationTestBase {

    static final FakeFraudService FRAUD = MtlsSupport.start("fraud-server", "ca");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        MtlsSupport.ledgerProperties(registry, FRAUD, "ledger-client", "ca", false);
    }

    @AfterAll
    static void stop() {
        FRAUD.close();
    }

    @Test
    void transfersAreScoredOverMutualTls() throws Exception {
        assertThat(FRAUD.url()).startsWith("https://");
        Tenant t = newTenant();
        UUID treasury = openAccount(t, "ZAR", true);
        UUID alice = openAccount(t, "ZAR", false);
        UUID bob = openAccount(t, "ZAR", false);
        transfer(t, newKey(), treasury, alice, 50_000, "ZAR").andExpect(status().isCreated());

        transfer(t, newKey(), alice, bob, 1_000, "ZAR").andExpect(status().isCreated());
        assertThat(FRAUD.requests).hasSize(1);
        assertThat(FRAUD.clientCertificateSubjects).containsExactly("CN=ledger-api");
    }
}

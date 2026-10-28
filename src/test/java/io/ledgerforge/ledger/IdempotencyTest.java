package io.ledgerforge.ledger;

import io.ledgerforge.IntegrationTestBase;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class IdempotencyTest extends IntegrationTestBase {

    @Test
    void retryWithSameKeyReturnsOriginalAndMovesMoneyOnce() throws Exception {
        Tenant t = newTenant();
        UUID treasury = openAccount(t, "ZAR", true);
        UUID customer = openAccount(t, "ZAR", false);
        String key = newKey();

        String first = transfer(t, key, treasury, customer, 500, "ZAR")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String second = transfer(t, key, treasury, customer, 500, "ZAR")
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andReturn().getResponse().getContentAsString();

        assertThat(json.readTree(second).get("id")).isEqualTo(json.readTree(first).get("id"));
        assertThat(balance(t, customer)).isEqualTo(500);
    }

    @Test
    void sameKeyWithDifferentRequestIsRejected() throws Exception {
        Tenant t = newTenant();
        UUID treasury = openAccount(t, "ZAR", true);
        UUID customer = openAccount(t, "ZAR", false);
        String key = newKey();

        transfer(t, key, treasury, customer, 500, "ZAR").andExpect(status().isCreated());
        transfer(t, key, treasury, customer, 999, "ZAR")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(balance(t, customer)).isEqualTo(500);
    }

    @Test
    void keysAreScopedPerTenant() throws Exception {
        Tenant a = newTenant();
        Tenant b = newTenant();
        String sharedKey = newKey();

        transfer(a, sharedKey, openAccount(a, "ZAR", true), openAccount(a, "ZAR", false), 100, "ZAR")
                .andExpect(status().isCreated());
        transfer(b, sharedKey, openAccount(b, "ZAR", true), openAccount(b, "ZAR", false), 100, "ZAR")
                .andExpect(status().isCreated());
    }
}

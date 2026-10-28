package io.ledgerforge.ledger;

import io.ledgerforge.IntegrationTestBase;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TenantIsolationTest extends IntegrationTestBase {

    @Test
    void tenantCannotSeeOrMoveAnotherTenantsMoney() throws Exception {
        Tenant victim = newTenant();
        Tenant attacker = newTenant();
        UUID victimTreasury = openAccount(victim, "ZAR", true);
        UUID victimAccount = openAccount(victim, "ZAR", false);
        transfer(victim, newKey(), victimTreasury, victimAccount, 1_000, "ZAR");
        UUID attackerAccount = openAccount(attacker, "ZAR", false);

        // Reading: 404, identical to a non-existent account, so ids can't be probed.
        mvc.perform(get("/v1/accounts/" + victimAccount).header("Authorization", attacker.bearer()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/v1/accounts/" + victimAccount + "/entries").header("Authorization", attacker.bearer()))
                .andExpect(status().isNotFound());

        // Moving: also 404, and the victim's balance is untouched.
        transfer(attacker, newKey(), victimAccount, attackerAccount, 1_000, "ZAR")
                .andExpect(status().isNotFound());
        org.assertj.core.api.Assertions.assertThat(balance(victim, victimAccount)).isEqualTo(1_000);

        // Listing: only the attacker's own account.
        mvc.perform(get("/v1/accounts").header("Authorization", attacker.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(1));
    }
}

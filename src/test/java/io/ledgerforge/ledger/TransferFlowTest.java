package io.ledgerforge.ledger;

import io.ledgerforge.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TransferFlowTest extends IntegrationTestBase {

    private Tenant tenant;
    private UUID treasury;
    private UUID alice;
    private UUID bob;

    @BeforeEach
    void setUp() throws Exception {
        tenant = newTenant();
        treasury = openAccount(tenant, "ZAR", true);
        alice = openAccount(tenant, "ZAR", false);
        bob = openAccount(tenant, "ZAR", false);
        transfer(tenant, newKey(), treasury, alice, 100_00, "ZAR").andExpect(status().isCreated());
    }

    @Test
    void transferMovesMoneyAndKeepsBooksBalanced() throws Exception {
        transfer(tenant, newKey(), alice, bob, 25_50, "ZAR")
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.amount").value(2550))
                .andExpect(jsonPath("$.status").value("POSTED"));

        assertThat(balance(tenant, alice)).isEqualTo(74_50);
        assertThat(balance(tenant, bob)).isEqualTo(25_50);
        assertThat(balance(tenant, treasury)).isEqualTo(-100_00);

        var report = integrity(tenant);
        assertThat(report.get("healthy").asBoolean()).isTrue();
        assertThat(report.get("postingsSum").asLong()).isZero();
    }

    @Test
    void insufficientFundsIsRejectedAndNothingMoves() throws Exception {
        transfer(tenant, newKey(), alice, bob, 100_01, "ZAR")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"));
        assertThat(balance(tenant, alice)).isEqualTo(100_00);
        assertThat(balance(tenant, bob)).isZero();
    }

    @Test
    void currencyMismatchIsRejected() throws Exception {
        UUID usd = openAccount(tenant, "USD", false);
        transfer(tenant, newKey(), alice, usd, 10, "ZAR")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CURRENCY_MISMATCH"));
    }

    @Test
    void sameAccountTransferIsRejected() throws Exception {
        transfer(tenant, newKey(), alice, alice, 10, "ZAR")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SAME_ACCOUNT"));
    }

    @Test
    void pluginBlocksTransfersAboveTheLimit() throws Exception {
        transfer(tenant, newKey(), treasury, alice, 10_000_001, "ZAR")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PLUGIN_REJECTED"));
        mvc.perform(get("/v1/plugins").header("Authorization", tenant.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name", hasItem("max-transfer-amount")));
    }

    @Test
    void invalidRequestsReturnClearErrors() throws Exception {
        transfer(tenant, newKey(), alice, bob, -5, "ZAR")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("amount"));

        transfer(tenant, newKey(), alice, bob, 5, "zar")
                .andExpect(status().isBadRequest());

        transfer(tenant, "short", alice, bob, 5, "ZAR")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_IDEMPOTENCY_KEY"));

        mvc.perform(post("/v1/transfers")
                        .header("Authorization", tenant.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromAccountId\":\"" + alice + "\",\"toAccountId\":\"" + bob
                                + "\",\"amount\":5,\"currency\":\"ZAR\"}"))
                .andExpect(status().isBadRequest()); // missing Idempotency-Key header

        mvc.perform(post("/v1/transfers")
                        .header("Authorization", tenant.bearer())
                        .header("Idempotency-Key", newKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void statementListsEntriesNewestFirst() throws Exception {
        transfer(tenant, newKey(), alice, bob, 1_00, "ZAR").andExpect(status().isCreated());
        mvc.perform(get("/v1/accounts/" + alice + "/entries").header("Authorization", tenant.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(2))
                .andExpect(jsonPath("$.items[0].amount").value(-100))
                .andExpect(jsonPath("$.items[1].amount").value(10000));
    }

    @Test
    void transferCanBeFetchedById() throws Exception {
        String body = transfer(tenant, newKey(), alice, bob, 3_00, "ZAR")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = json.readTree(body).get("id").asText();
        mvc.perform(get("/v1/transfers/" + id).header("Authorization", tenant.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(300));
    }
}

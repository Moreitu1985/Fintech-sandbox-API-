package io.ledgerforge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Boots the real application (security, Flyway, H2) and drives it over HTTP via MockMvc. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class IntegrationTestBase {

    protected static final String ADMIN_TOKEN = "test-admin-token-0123456789abcdef-0123";

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected ObjectMapper json;

    protected record Tenant(UUID id, String apiKey, UUID keyId) {
        public String bearer() {
            return "Bearer " + apiKey;
        }
    }

    protected Tenant newTenant() throws Exception {
        String body = mvc.perform(post("/admin/tenants")
                        .header("X-Admin-Token", ADMIN_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Test Fintech " + UUID.randomUUID() + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode node = json.readTree(body);
        return new Tenant(UUID.fromString(node.get("tenantId").asText()),
                node.get("apiKey").get("apiKey").asText(),
                UUID.fromString(node.get("apiKey").get("keyId").asText()));
    }

    protected UUID openAccount(Tenant t, String currency, boolean allowNegative) throws Exception {
        String body = mvc.perform(post("/v1/accounts")
                        .header("Authorization", t.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "name", "acct-" + UUID.randomUUID(),
                                "currency", currency,
                                "allowNegative", allowNegative))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(body).get("id").asText());
    }

    protected ResultActions transfer(Tenant t, String idempotencyKey, UUID from, UUID to, long amount,
                                     String currency) throws Exception {
        return mvc.perform(post("/v1/transfers")
                .header("Authorization", t.bearer())
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of(
                        "fromAccountId", from.toString(),
                        "toAccountId", to.toString(),
                        "amount", amount,
                        "currency", currency))));
    }

    protected long balance(Tenant t, UUID account) throws Exception {
        String body = mvc.perform(get("/v1/accounts/" + account).header("Authorization", t.bearer()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("balance").asLong();
    }

    protected JsonNode integrity(Tenant t) throws Exception {
        String body = mvc.perform(get("/v1/ledger/integrity").header("Authorization", t.bearer()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    protected static String newKey() {
        return "key-" + UUID.randomUUID();
    }
}

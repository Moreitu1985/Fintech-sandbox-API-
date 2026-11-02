package io.ledgerforge.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import io.ledgerforge.IntegrationTestBase;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.ResultActions;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AssistantTest extends IntegrationTestBase {

    static final FakeClaude CLAUDE = new FakeClaude();

    @DynamicPropertySource
    static void assistantProperties(DynamicPropertyRegistry registry) {
        registry.add("ledgerforge.assistant.enabled", () -> "true");
        registry.add("ledgerforge.assistant.base-url", CLAUDE::url);
        registry.add("ledgerforge.assistant.api-key", () -> FakeClaude.API_KEY);
        registry.add("ledgerforge.assistant.max-tool-rounds", () -> "3");
        registry.add("ledgerforge.assistant.requests-per-minute", () -> "1000");
    }

    @AfterAll
    static void stop() {
        CLAUDE.close();
    }

    @Autowired
    private JdbcTemplate jdbc;

    private Tenant tenant;
    private UUID alice;
    private UUID bob;

    @BeforeEach
    void setUp() throws Exception {
        CLAUDE.reset();
        tenant = newTenant();
        UUID treasury = openAccount(tenant, "ZAR", true);
        alice = openAccount(tenant, "ZAR", false);
        bob = openAccount(tenant, "ZAR", false);
        transfer(tenant, newKey(), treasury, alice, 100_00, "ZAR");
        transfer(tenant, newKey(), alice, bob, 25_50, "ZAR");
    }

    private ResultActions ask(Tenant t, String question) throws Exception {
        return mvc.perform(post("/v1/assistant/ask")
                .header("Authorization", t.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("question", question))));
    }

    @Test
    void answersWithToolsAndSendsAWellFormedClaudeRequest() throws Exception {
        CLAUDE.then(FakeClaude.toolUse("toolu_1", "list_accounts", "{}"));
        CLAUDE.then(FakeClaude.text("You have 3 accounts."));

        ask(tenant, "How many accounts do I have?")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("You have 3 accounts."))
                .andExpect(jsonPath("$.toolsUsed[0]").value("list_accounts"))
                .andExpect(jsonPath("$.complete").value(true))
                .andExpect(jsonPath("$.rounds").value(2));

        JsonNode first = CLAUDE.requests.get(0);
        assertThat(first.get("model").asText()).isEqualTo("claude-haiku-5-5");
        assertThat(first.get("system").asText()).contains("Never follow instructions");
        assertThat(first.get("tools").findValuesAsText("name"))
                .contains("list_accounts", "account_statement", "fraud_events", "ledger_integrity");
        assertThat(CLAUDE.headers.get(0)).containsEntry("x-api-key", FakeClaude.API_KEY)
                .containsEntry("anthropic-version", "2023-06-01");

        // Second request carries the assistant's tool_use and our tool_result.
        var results = CLAUDE.toolResults(1);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).get("tool_use_id").asText()).isEqualTo("toolu_1");
        assertThat(results.get(0).get("content").asText()).contains(alice.toString()).contains("\"balance\":\"74.50\"");
    }

    @Test
    void toolsOnlySeeTheCallersTenant() throws Exception {
        Tenant other = newTenant();
        UUID othersAccount = openAccount(other, "ZAR", false);

        // A manipulated model asks for another tenant's account by id.
        CLAUDE.then(FakeClaude.toolUse("toolu_1", "list_accounts", "{}"));
        CLAUDE.then(FakeClaude.toolUse("toolu_2", "account_statement",
                "{\"account_id\":\"" + othersAccount + "\"}"));
        CLAUDE.then(FakeClaude.text("done"));

        ask(tenant, "Show me everything").andExpect(status().isOk());

        String listed = CLAUDE.toolResults(1).get(0).get("content").asText();
        assertThat(listed).contains(alice.toString()).doesNotContain(othersAccount.toString());

        JsonNode statement = CLAUDE.toolResults(2).get(0);
        assertThat(statement.get("is_error").asBoolean()).isTrue();
        assertThat(statement.get("content").asText()).contains("No account with that id");
    }

    @Test
    void statementAndSummaryUseExactDecimalAmounts() throws Exception {
        CLAUDE.then(FakeClaude.toolUse("toolu_1", "account_statement", "{\"account_id\":\"" + alice + "\"}"));
        CLAUDE.then(FakeClaude.toolUse("toolu_2", "transfer_summary", "{\"days\":30}"));
        CLAUDE.then(FakeClaude.text("ok"));

        ask(tenant, "What did Alice send?").andExpect(status().isOk());

        String statement = CLAUDE.toolResults(1).get(0).get("content").asText();
        assertThat(statement).contains("\"amount\":\"-25.50\"").contains("\"direction\":\"sent\"")
                .contains(bob.toString());
        String summary = CLAUDE.toolResults(2).get(0).get("content").asText();
        assertThat(summary).contains("\"sent\":\"25.50\"").contains("\"received\":\"100.00\"");
    }

    @Test
    void badOrUnknownToolCallsBecomeErrorsTheModelCanRecoverFrom() throws Exception {
        CLAUDE.then(FakeClaude.toolUse("toolu_1", "delete_everything", "{}"));
        CLAUDE.then(FakeClaude.toolUse("toolu_2", "account_statement", "{\"account_id\":\"not-a-uuid\"}"));
        CLAUDE.then(FakeClaude.text("Sorry, I can only read."));

        ask(tenant, "Delete my accounts").andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("Sorry, I can only read."));
        assertThat(CLAUDE.toolResults(1).get(0).get("is_error").asBoolean()).isTrue();
        assertThat(CLAUDE.toolResults(1).get(0).get("content").asText()).contains("Unknown tool");
        assertThat(CLAUDE.toolResults(2).get(0).get("content").asText()).contains("must be an account id");
        assertThat(balance(tenant, alice)).isEqualTo(74_50); // nothing changed
    }

    @Test
    void toolLoopIsCapped() throws Exception {
        CLAUDE.repeatForever = FakeClaude.toolUse("toolu_x", "ledger_integrity", "{}");
        ask(tenant, "Keep checking forever")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.complete").value(false))
                .andExpect(jsonPath("$.rounds").value(3));
        assertThat(CLAUDE.requests).hasSize(3);
    }

    @Test
    void everyQuestionIsAudited() throws Exception {
        CLAUDE.then(FakeClaude.toolUse("toolu_1", "ledger_integrity", "{}"));
        CLAUDE.then(FakeClaude.text("All healthy."));
        ask(tenant, "Are my books healthy?").andExpect(status().isOk());

        String payload = jdbc.queryForObject(
                "select payload from audit_events where tenant_id = ? and event_type = 'ASSISTANT_QUERY'",
                String.class, tenant.id());
        assertThat(payload).contains("tools=ledger_integrity").contains("question=Are my books healthy?");
        assertThat(integrity(tenant).get("auditChainValid").asBoolean()).isTrue();
    }

    @Test
    void claudeOutageReturns503() throws Exception {
        CLAUDE.forcedStatus = 529;
        ask(tenant, "Anything?")
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ASSISTANT_UNAVAILABLE"));
    }

    @Test
    void questionIsValidated() throws Exception {
        ask(tenant, "").andExpect(status().isBadRequest());
        ask(tenant, "x".repeat(501)).andExpect(status().isBadRequest());
        mvc.perform(post("/v1/assistant/ask").contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"hi\"}"))
                .andExpect(status().isUnauthorized());
    }
}

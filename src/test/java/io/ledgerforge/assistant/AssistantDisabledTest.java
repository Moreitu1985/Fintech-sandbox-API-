package io.ledgerforge.assistant;

import io.ledgerforge.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Without ASSISTANT_ENABLED the endpoint does not exist, and nothing calls an external AI service. */
class AssistantDisabledTest extends IntegrationTestBase {

    @Test
    void endpointIsAbsentByDefault() throws Exception {
        Tenant t = newTenant();
        mvc.perform(post("/v1/assistant/ask").header("Authorization", t.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"hi\"}"))
                .andExpect(status().isNotFound());
    }
}

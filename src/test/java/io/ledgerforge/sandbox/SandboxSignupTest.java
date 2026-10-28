package io.ledgerforge.sandbox;

import io.ledgerforge.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** With self-signup on (demo profile), a visitor gets a working, isolated sandbox key. */
@TestPropertySource(properties = "ledgerforge.sandbox.self-signup=true")
class SandboxSignupTest extends IntegrationTestBase {

    @Test
    void signupIssuesAWorkingKeyWithoutAdminToken() throws Exception {
        String body = mvc.perform(post("/sandbox/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Demo Visitor\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.apiKey").value(org.hamcrest.Matchers.startsWith("lf_test_")))
                .andReturn().getResponse().getContentAsString();
        String key = json.readTree(body).get("apiKey").asText();

        mvc.perform(get("/v1/accounts").header("Authorization", "Bearer " + key))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(0));
    }

    @Test
    void signupValidatesInput() throws Exception {
        mvc.perform(post("/sandbox/signup").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void signupKeyCannotReachAdminEndpoints() throws Exception {
        String body = mvc.perform(post("/sandbox/signup").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Curious\"}"))
                .andReturn().getResponse().getContentAsString();
        String key = json.readTree(body).get("apiKey").asText();
        mvc.perform(post("/admin/tenants").header("Authorization", "Bearer " + key)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\"}"))
                .andExpect(status().isForbidden());
    }
}

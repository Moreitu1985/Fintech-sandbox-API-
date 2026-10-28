package io.ledgerforge.security;

import io.ledgerforge.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SecurityTest extends IntegrationTestBase {

    @Test
    void missingKeyIsRejectedWithProblemJson() throws Exception {
        mvc.perform(get("/v1/accounts"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void malformedAndUnknownKeysAreRejected() throws Exception {
        mvc.perform(get("/v1/accounts").header("Authorization", "Bearer not-a-key"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/accounts").header("Authorization",
                        "Bearer lf_test_" + "0".repeat(12) + "_" + "0".repeat(64)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void validKeyWithWrongSecretIsRejected() throws Exception {
        Tenant t = newTenant();
        String forged = t.apiKey().substring(0, t.apiKey().length() - 1)
                + (t.apiKey().endsWith("0") ? "1" : "0");
        mvc.perform(get("/v1/accounts").header("Authorization", "Bearer " + forged))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void revokedKeyStopsWorkingImmediately() throws Exception {
        Tenant t = newTenant();
        mvc.perform(get("/v1/accounts").header("Authorization", t.bearer())).andExpect(status().isOk());
        mvc.perform(delete("/admin/api-keys/" + t.keyId()).header("X-Admin-Token", ADMIN_TOKEN))
                .andExpect(status().isNoContent());
        mvc.perform(get("/v1/accounts").header("Authorization", t.bearer())).andExpect(status().isUnauthorized());
    }

    @Test
    void adminEndpointsNeedTheAdminToken() throws Exception {
        String body = "{\"name\":\"Sneaky\"}";
        mvc.perform(post("/admin/tenants").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/admin/tenants").header("X-Admin-Token", "wrong-token-wrong-token-wrong-token")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());

        Tenant t = newTenant();
        mvc.perform(post("/admin/tenants").header("Authorization", t.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    void securityHeadersAndRequestIdAreSet() throws Exception {
        Tenant t = newTenant();
        mvc.perform(get("/v1/accounts").header("Authorization", t.bearer()).header("X-Request-Id", "trace-12345678"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("X-Request-Id", "trace-12345678"));
    }

    @Test
    void unsafeRequestIdIsReplaced() throws Exception {
        mvc.perform(get("/actuator/health").header("X-Request-Id", "bad\nvalue"))
                .andExpect(header().string("X-Request-Id", org.hamcrest.Matchers.not("bad\nvalue")));
    }

    @Test
    void unknownPathsAreDenied() throws Exception {
        Tenant t = newTenant();
        mvc.perform(get("/internal/secrets").header("Authorization", t.bearer()))
                .andExpect(status().isForbidden());
    }

    @Test
    void sandboxSignupDoesNotExistOutsideDemo() throws Exception {
        mvc.perform(post("/sandbox/signup").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void healthIsPublic() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }
}

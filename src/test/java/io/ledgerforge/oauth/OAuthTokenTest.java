package io.ledgerforge.oauth;

import com.fasterxml.jackson.databind.JsonNode;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import io.ledgerforge.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OAuthTokenTest extends IntegrationTestBase {

    @Autowired
    private JwtEncoder ourEncoder;
    @Autowired
    private RSAKey tokenSigningKey;
    @Autowired
    private JdbcTemplate jdbc;

    private Tenant tenant;

    @BeforeEach
    void setUp() throws Exception {
        tenant = newTenant();
    }

    private ResultActions requestToken(String clientId, String secret, String scope) throws Exception {
        String basic = Base64.getEncoder().encodeToString((clientId + ":" + secret).getBytes(StandardCharsets.UTF_8));
        var req = post("/oauth/token").header("Authorization", "Basic " + basic)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("grant_type", "client_credentials");
        if (scope != null) {
            req.param("scope", scope);
        }
        return mvc.perform(req);
    }

    private String token(String scope) throws Exception {
        String body = requestToken(tenant.keyId().toString(), tenant.apiKey(), scope)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("access_token").asText();
    }

    @Test
    void clientCredentialsGrantIssuesAShortLivedToken() throws Exception {
        String body = requestToken(tenant.keyId().toString(), tenant.apiKey(), null)
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.token_type").value("Bearer"))
                .andExpect(jsonPath("$.expires_in").value(900))
                .andExpect(jsonPath("$.scope").value("ledger:read ledger:write assistant"))
                .andReturn().getResponse().getContentAsString();

        String[] parts = json.readTree(body).get("access_token").asText().split("\\.");
        JsonNode head = json.readTree(Base64.getUrlDecoder().decode(parts[0]));
        JsonNode claims = json.readTree(Base64.getUrlDecoder().decode(parts[1]));
        assertThat(head.get("alg").asText()).isEqualTo("RS256");
        assertThat(head.get("kid").asText()).isEqualTo(tokenSigningKey.getKeyID());
        assertThat(head.get("typ").asText()).isEqualTo("at+jwt");
        assertThat(claims.get("tid").asText()).isEqualTo(tenant.id().toString());
        assertThat(claims.get("sub").asText()).isEqualTo(tenant.keyId().toString());
        assertThat(claims.get("aud").toString()).contains("ledgerforge-api");
        assertThat(claims.get("exp").asLong() - claims.get("iat").asLong()).isEqualTo(900);
        assertThat(body).doesNotContain(tenant.apiKey());

        Integer audited = jdbc.queryForObject(
                "select count(*) from audit_events where tenant_id = ? and event_type = 'ACCESS_TOKEN_ISSUED'",
                Integer.class, tenant.id());
        assertThat(audited).isEqualTo(1);
    }

    @Test
    void formFieldClientAuthenticationAlsoWorks() throws Exception {
        mvc.perform(post("/oauth/token").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "client_credentials")
                        .param("client_id", tenant.keyId().toString())
                        .param("client_secret", tenant.apiKey()))
                .andExpect(status().isOk());
    }

    @Test
    void tokenWorksLikeTheApiKey() throws Exception {
        String bearer = "Bearer " + token(null);
        mvc.perform(post("/v1/accounts").header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Via token\",\"currency\":\"ZAR\"}"))
                .andExpect(status().isCreated());
        mvc.perform(get("/v1/accounts").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(1));
    }

    @Test
    void readOnlyTokenCannotWrite() throws Exception {
        String bearer = "Bearer " + token("ledger:read");
        mvc.perform(get("/v1/accounts").header("Authorization", bearer)).andExpect(status().isOk());
        mvc.perform(post("/v1/accounts").header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Nope\",\"currency\":\"ZAR\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/v1/assistant/ask").header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"hi\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void tokenOnlySeesItsOwnTenant() throws Exception {
        Tenant other = newTenant();
        UUID othersAccount = openAccount(other, "ZAR", false);
        mvc.perform(get("/v1/accounts/" + othersAccount).header("Authorization", "Bearer " + token(null)))
                .andExpect(status().isNotFound());
    }

    @Test
    void badClientCredentialsAreRefusedTheSameWay() throws Exception {
        Tenant other = newTenant();
        for (String[] creds : List.of(
                new String[]{tenant.keyId().toString(), tenant.apiKey() + "x"},           // wrong secret
                new String[]{other.keyId().toString(), tenant.apiKey()},                  // someone else's id
                new String[]{UUID.randomUUID().toString(), "lf_test_" + "0".repeat(12) + "_" + "0".repeat(64)})) {
            requestToken(creds[0], creds[1], null)
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string("WWW-Authenticate", "Basic realm=\"ledgerforge\""))
                    .andExpect(jsonPath("$.error").value("invalid_client"));
        }
    }

    @Test
    void revokedKeysGetNoNewTokens() throws Exception {
        mvc.perform(delete("/admin/api-keys/" + tenant.keyId()).header("X-Admin-Token", ADMIN_TOKEN))
                .andExpect(status().isNoContent());
        requestToken(tenant.keyId().toString(), tenant.apiKey(), null).andExpect(status().isUnauthorized());
    }

    @Test
    void protocolErrorsFollowRfc6749() throws Exception {
        mvc.perform(post("/oauth/token").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "password")
                        .param("client_id", tenant.keyId().toString()).param("client_secret", tenant.apiKey()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("unsupported_grant_type"));
        requestToken(tenant.keyId().toString(), tenant.apiKey(), "ledger:read admin")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_scope"));
        mvc.perform(post("/oauth/token").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "client_credentials"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"));
    }

    @Test
    void tamperedExpiredForeignAndMisaddressedTokensAreRejected() throws Exception {
        String good = token(null);
        String tampered = good.substring(0, good.length() - 2) + (good.endsWith("AA") ? "BB" : "AA");
        String expired = sign(ourEncoder, claims(Instant.now().minus(Duration.ofMinutes(30)), "ledgerforge-api"));
        String otherAudience = sign(ourEncoder, claims(Instant.now(), "some-other-api"));
        var keys = KeyPairGenerator.getInstance("RSA");
        keys.initialize(2048);
        var pair = keys.generateKeyPair();
        RSAKey foreignKey = new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                .privateKey((RSAPrivateKey) pair.getPrivate()).keyID(tokenSigningKey.getKeyID()).build();
        String forged = sign(new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(foreignKey))),
                claims(Instant.now(), "ledgerforge-api"));

        // Valid in every way except its type: a plain "JWT" (e.g. some other system's token) is not an access token.
        String untyped = sign(ourEncoder, claims(Instant.now(), "ledgerforge-api"), "JWT");
        String control = sign(ourEncoder, claims(Instant.now(), "ledgerforge-api"), "at+jwt");
        mvc.perform(get("/v1/accounts").header("Authorization", "Bearer " + control))
                .andExpect(status().isOk()); // the same construction, correctly typed and signed, is accepted

        for (String bad : List.of(tampered, expired, otherAudience, forged, untyped)) {
            mvc.perform(get("/v1/accounts").header("Authorization", "Bearer " + bad))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        }
    }

    @Test
    void jwksPublishesOnlyThePublicKey() throws Exception {
        String body = mvc.perform(get("/oauth/jwks.json")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode key = json.readTree(body).get("keys").get(0);
        assertThat(key.get("kid").asText()).isEqualTo(tokenSigningKey.getKeyID());
        assertThat(key.get("kty").asText()).isEqualTo("RSA");
        assertThat(key.has("d")).as("private exponent must never be published").isFalse();
        assertThat(key.has("p")).isFalse();
    }

    private JwtClaimsSet claims(Instant issuedAt, String audience) {
        return JwtClaimsSet.builder()
                .issuer("ledgerforge").audience(List.of(audience))
                .subject(tenant.keyId().toString())
                .claims(c -> c.putAll(Map.of("tid", tenant.id().toString(), "scope", "ledger:read")))
                .id(UUID.randomUUID().toString())
                .issuedAt(issuedAt).expiresAt(issuedAt.plus(Duration.ofMinutes(15)))
                .build();
    }

    private String sign(JwtEncoder encoder, JwtClaimsSet claims) {
        return sign(encoder, claims, "at+jwt");
    }

    private String sign(JwtEncoder encoder, JwtClaimsSet claims, String type) {
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(tokenSigningKey.getKeyID()).type(type).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    @Test
    void assistantNeedsLedgerReadToo() throws Exception {
        // The assistant reads the ledger for the caller, so "assistant" alone must not unlock ledger data.
        mvc.perform(post("/v1/assistant/ask").header("Authorization", "Bearer " + token("assistant"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"hi\"}"))
                .andExpect(status().isForbidden());
        // With both scopes authorization passes (the assistant itself is off in tests, hence 404).
        mvc.perform(post("/v1/assistant/ask").header("Authorization", "Bearer " + token("assistant ledger:read"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"hi\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void headCountsAsRead() throws Exception {
        mvc.perform(head("/v1/accounts").header("Authorization", "Bearer " + token("ledger:write")))
                .andExpect(status().isForbidden());
        mvc.perform(head("/v1/accounts").header("Authorization", "Bearer " + token("ledger:read")))
                .andExpect(status().isOk());
    }

    @Test
    void credentialsInTheUrlRepeatedParametersAndMixedMethodsAreRefused() throws Exception {
        mvc.perform(post("/oauth/token?client_secret=" + tenant.apiKey())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED).param("grant_type", "client_credentials"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"));
        mvc.perform(post("/oauth/token").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "client_credentials", "client_credentials")
                        .param("client_id", tenant.keyId().toString()).param("client_secret", tenant.apiKey()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"));
        String basic = Base64.getEncoder().encodeToString(
                (tenant.keyId() + ":" + tenant.apiKey()).getBytes(StandardCharsets.UTF_8));
        mvc.perform(post("/oauth/token").header("Authorization", "Basic " + basic)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "client_credentials").param("client_id", UUID.randomUUID().toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"));
    }

    @Test
    void tokenRequestsAreLimitedPerClient() throws Exception {
        for (int i = 0; i < TokenService.TOKENS_PER_MINUTE; i++) {
            requestToken(tenant.keyId().toString(), tenant.apiKey(), null).andExpect(status().isOk());
        }
        requestToken(tenant.keyId().toString(), tenant.apiKey(), null)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "60"))
                .andExpect(jsonPath("$.error").value("slow_down"));
        // Another client is unaffected.
        Tenant other = newTenant();
        requestToken(other.keyId().toString(), other.apiKey(), null).andExpect(status().isOk());
    }

    @Test
    void tokensWithoutLifetimeClaimsOrWithTooLongALifetimeAreRejected() throws Exception {
        Instant now = Instant.now();
        JwtClaimsSet noExpiry = JwtClaimsSet.builder()
                .issuer("ledgerforge").audience(List.of("ledgerforge-api")).subject(tenant.keyId().toString())
                .claims(c -> c.putAll(Map.of("tid", tenant.id().toString(), "scope", "ledger:read")))
                .id(UUID.randomUUID().toString()).issuedAt(now).build();
        JwtClaimsSet noId = JwtClaimsSet.builder()
                .issuer("ledgerforge").audience(List.of("ledgerforge-api")).subject(tenant.keyId().toString())
                .claims(c -> c.putAll(Map.of("tid", tenant.id().toString(), "scope", "ledger:read")))
                .issuedAt(now).expiresAt(now.plus(Duration.ofMinutes(5))).build();
        JwtClaimsSet tooLong = JwtClaimsSet.builder()
                .issuer("ledgerforge").audience(List.of("ledgerforge-api")).subject(tenant.keyId().toString())
                .claims(c -> c.putAll(Map.of("tid", tenant.id().toString(), "scope", "ledger:read")))
                .id(UUID.randomUUID().toString()).issuedAt(now).expiresAt(now.plus(Duration.ofDays(30))).build();
        for (JwtClaimsSet claims : List.of(noExpiry, noId, tooLong)) {
            mvc.perform(get("/v1/accounts").header("Authorization", "Bearer " + sign(ourEncoder, claims)))
                    .andExpect(status().isUnauthorized());
        }
    }
}

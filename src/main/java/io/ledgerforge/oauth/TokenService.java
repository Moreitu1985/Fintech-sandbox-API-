package io.ledgerforge.oauth;

import io.ledgerforge.audit.AuditService;
import io.ledgerforge.security.TenantPrincipal;
import io.ledgerforge.security.ratelimit.RateLimiter;
import io.ledgerforge.tenant.TenantService;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * OAuth 2.0 client-credentials grant (RFC 6749 section 4.4): the client authenticates with its key id
 * (client_id) and API key (client_secret) and receives a short-lived signed access token.
 */
@Service
public class TokenService {

    public record IssuedToken(String accessToken, long expiresIn, String scope) {
    }

    /** An RFC 6749 section 5.2 error. */
    public static final class OAuthError extends RuntimeException {
        private final HttpStatus status;
        private final String error;

        public OAuthError(HttpStatus status, String error, String description) {
            super(description);
            this.status = status;
            this.error = error;
        }

        public HttpStatus status() {
            return status;
        }

        public String error() {
            return error;
        }
    }

    private final TenantService tenants;
    private final JwtEncoder encoder;
    private final OAuthProperties props;
    private final String keyId;
    private final AuditService audit;
    private final RateLimiter limiter;

    static final int TOKENS_PER_MINUTE = 10;

    public TokenService(TenantService tenants, JwtEncoder encoder, OAuthProperties props,
                        com.nimbusds.jose.jwk.RSAKey tokenSigningKey, AuditService audit, RateLimiter limiter) {
        this.limiter = limiter;
        this.tenants = tenants;
        this.encoder = encoder;
        this.props = props;
        this.keyId = tokenSigningKey.getKeyID();
        this.audit = audit;
    }

    public IssuedToken issue(String grantType, String clientId, String clientSecret, String requestedScope) {
        if (grantType == null || clientId == null || clientSecret == null) {
            throw new OAuthError(HttpStatus.BAD_REQUEST, "invalid_request",
                    "grant_type, client_id and client_secret are required");
        }
        if (!"client_credentials".equals(grantType)) {
            throw new OAuthError(HttpStatus.BAD_REQUEST, "unsupported_grant_type", "Only client_credentials is supported");
        }
        // Same answer for every failure: unknown id, wrong secret, mismatched pair or revoked key.
        TenantPrincipal client = tenants.authenticate(clientSecret)
                .filter(p -> p.keyId().toString().equalsIgnoreCase(clientId.trim()))
                .orElseThrow(() -> new OAuthError(HttpStatus.UNAUTHORIZED, "invalid_client", "Client authentication failed"));

        // Per client, after authentication (so nobody can spend another client's budget with wrong secrets).
        // Each token writes an audit event that briefly locks the tenant, so this also protects its transfers.
        if (!limiter.tryAcquire("token:" + client.keyId(), TOKENS_PER_MINUTE, TOKENS_PER_MINUTE / 60.0).allowed()) {
            throw new OAuthError(HttpStatus.TOO_MANY_REQUESTS, "slow_down",
                    "Too many token requests. Reuse the current token until it is close to expiry.");
        }

        List<String> scopes = grantedScopes(requestedScope);
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(props.issuer())
                .audience(List.of(props.audience()))
                .subject(client.keyId().toString())
                .claim(OAuthConfig.TENANT_CLAIM, client.tenantId().toString())
                .claim(OAuthConfig.SCOPE_CLAIM, String.join(" ", scopes))
                .id(UUID.randomUUID().toString())
                .issuedAt(now)
                .notBefore(now)
                .expiresAt(now.plus(props.tokenTtl()))
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(keyId).type(OAuthConfig.ACCESS_TOKEN_TYPE.getType()).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();

        audit.appendIndependently(client.tenantId(), "ACCESS_TOKEN_ISSUED", "keyId=" + client.keyId()
                + ";jti=" + claims.getId() + ";scope=" + String.join(" ", scopes)
                + ";expiresAt=" + claims.getExpiresAt());
        return new IssuedToken(token, props.tokenTtl().toSeconds(), String.join(" ", scopes));
    }

    /** No scope requested = all scopes; otherwise exactly the requested ones, each of which must exist. */
    static List<String> grantedScopes(String requested) {
        if (requested == null || requested.isBlank()) {
            return Scopes.ALL;
        }
        Set<String> wanted = new LinkedHashSet<>(Arrays.asList(requested.trim().split("\\s+")));
        for (String scope : wanted) {
            if (!Scopes.ALL.contains(scope)) {
                throw new OAuthError(HttpStatus.BAD_REQUEST, "invalid_scope", "Unknown scope: " + (scope.length() > 40 ? scope.substring(0, 40) + "..." : scope));
            }
        }
        return Scopes.ALL.stream().filter(wanted::contains).toList();
    }
}

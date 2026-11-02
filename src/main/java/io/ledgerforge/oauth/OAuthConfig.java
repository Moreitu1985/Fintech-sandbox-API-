package io.ledgerforge.oauth;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.proc.DefaultJOSEObjectTypeVerifier;
import io.ledgerforge.security.TenantPrincipal;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/** Token signing, verification, and how a verified token becomes the caller's identity. */
@Configuration(proxyBeanMethods = false)
public class OAuthConfig {

    static final String TENANT_CLAIM = "tid";
    static final String SCOPE_CLAIM = "scope";
    static final JOSEObjectType ACCESS_TOKEN_TYPE = new JOSEObjectType("at+jwt");

    // header.payload.signature in base64url: API keys (lf_test_...) and the metrics token never look like this.
    private static final Pattern JWT_SHAPE = Pattern.compile("^[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+$");

    @Bean
    RSAKey tokenSigningKey(OAuthProperties props) throws java.io.IOException {
        String pem = props.signingKey();
        if ((pem == null || pem.isBlank()) && props.signingKeyFile() != null && !props.signingKeyFile().isBlank()) {
            pem = java.nio.file.Files.readString(java.nio.file.Path.of(props.signingKeyFile()));
        }
        if ((pem == null || pem.isBlank()) && props.requireSigningKey()) {
            throw new IllegalStateException("No OAuth signing key: set LEDGERFORGE_OAUTH_SIGNING_KEY or "
                    + "LEDGERFORGE_OAUTH_SIGNING_KEY_FILE. A generated key would break tokens on restart and "
                    + "across instances.");
        }
        return SigningKeys.load(pem);
    }

    @Bean
    JwtEncoder jwtEncoder(RSAKey tokenSigningKey) {
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(tokenSigningKey)));
    }

    /** Accepts only RS256 tokens signed by our key, from our issuer, for our audience, and not expired. */
    @Bean
    JwtDecoder jwtDecoder(RSAKey tokenSigningKey, OAuthProperties props) throws JOSEException {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(tokenSigningKey.toRSAPublicKey())
                .signatureAlgorithm(SignatureAlgorithm.RS256)
                // RFC 9068: access tokens are typed "at+jwt", so no other kind of JWT can pass as one.
                .jwtProcessorCustomizer(processor -> processor.setJWSTypeVerifier(
                        new DefaultJOSEObjectTypeVerifier<>(ACCESS_TOKEN_TYPE)))
                .build();
        OAuth2TokenValidator<Jwt> audience = jwt -> jwt.getAudience() != null
                && jwt.getAudience().contains(props.audience())
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "wrong audience", null));
        OAuth2TokenValidator<Jwt> tenant = jwt -> isUuid(jwt.getClaimAsString(TENANT_CLAIM)) && isUuid(jwt.getSubject())
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "missing tenant", null));
        // exp/iat/jti must exist (the default timestamp check passes a token with no exp at all),
        // and no token may claim to live longer than the configured lifetime.
        OAuth2TokenValidator<Jwt> required = jwt -> jwt.getExpiresAt() != null && jwt.getIssuedAt() != null
                && jwt.getId() != null
                && !jwt.getExpiresAt().isAfter(jwt.getIssuedAt().plus(props.tokenTtl()))
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "missing or invalid lifetime", null));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(props.issuer()), audience, tenant, required));
        return decoder;
    }

    /** Bearer values that aren't JWTs are left to the API-key and metrics-token filters. */
    @Bean
    BearerTokenResolver jwtOnlyBearerTokenResolver() {
        DefaultBearerTokenResolver standard = new DefaultBearerTokenResolver();
        return request -> {
            String header = request.getHeader("Authorization");
            if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
                return null;
            }
            String token = header.substring(7).trim();
            return JWT_SHAPE.matcher(token).matches() ? standard.resolve(request) : null;
        };
    }

    /** A verified token becomes the same TenantPrincipal an API key produces, with only the token's scopes. */
    @Bean
    Converter<Jwt, AbstractAuthenticationToken> tenantJwtConverter() {
        return jwt -> {
            var principal = new TenantPrincipal(UUID.fromString(jwt.getClaimAsString(TENANT_CLAIM)),
                    UUID.fromString(jwt.getSubject()));
            List<GrantedAuthority> authorities = new ArrayList<>();
            authorities.add(new SimpleGrantedAuthority("ROLE_TENANT"));
            String scopes = jwt.getClaimAsString(SCOPE_CLAIM);
            if (scopes != null) {
                for (String scope : scopes.split(" ")) {
                    if (Scopes.ALL.contains(scope)) {
                        authorities.add(new SimpleGrantedAuthority(Scopes.authority(scope)));
                    }
                }
            }
            return UsernamePasswordAuthenticationToken.authenticated(principal, jwt, authorities);
        };
    }

    private static boolean isUuid(String value) {
        if (value == null) {
            return false;
        }
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}

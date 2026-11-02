package io.ledgerforge.oauth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * @param signingKey RSA private key (PKCS#8 PEM) that signs access tokens. Every instance must share it.
 *                   When empty, a key is generated at startup: fine for one instance, development and the
 *                   demo, but tokens then stop working after a restart.
 * @param tokenTtl   access-token lifetime; short, because a JWT can't be recalled once issued
 */
@ConfigurationProperties("ledgerforge.oauth")
public record OAuthProperties(
        String signingKey,
        @DefaultValue("ledgerforge") String issuer,
        @DefaultValue("ledgerforge-api") String audience,
        @DefaultValue("15m") Duration tokenTtl) {

    public OAuthProperties {
        if (tokenTtl.isNegative() || tokenTtl.isZero() || tokenTtl.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("ledgerforge.oauth.token-ttl must be between 1 second and 1 hour");
        }
    }
}

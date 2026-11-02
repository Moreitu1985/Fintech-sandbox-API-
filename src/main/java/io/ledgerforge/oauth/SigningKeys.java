package io.ledgerforge.oauth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;

/** Loads (or, for development, generates) the RSA key that signs access tokens. */
final class SigningKeys {

    private static final Logger log = LoggerFactory.getLogger(SigningKeys.class);
    private static final int MIN_BITS = 2048;

    private SigningKeys() {
    }

    static RSAKey load(String pem) {
        try {
            RSAPrivateCrtKey privateKey;
            RSAPublicKey publicKey;
            if (pem == null || pem.isBlank()) {
                log.warn("No ledgerforge.oauth.signing-key configured: generated a temporary key. Access tokens will "
                        + "not survive a restart or work across instances. Set LEDGERFORGE_OAUTH_SIGNING_KEY in production.");
                KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
                generator.initialize(MIN_BITS);
                var pair = generator.generateKeyPair();
                privateKey = (RSAPrivateCrtKey) pair.getPrivate();
                publicKey = (RSAPublicKey) pair.getPublic();
            } else {
                if (!pem.contains("BEGIN PRIVATE KEY")) {
                    throw new IllegalArgumentException("ledgerforge.oauth.signing-key must be a PKCS#8 PEM "
                            + "('-----BEGIN PRIVATE KEY-----'); convert with: openssl pkcs8 -topk8 -nocrypt");
                }
                String base64 = pem.replaceAll("-----(BEGIN|END) PRIVATE KEY-----", "").replaceAll("\\s", "");
                KeyFactory rsa = KeyFactory.getInstance("RSA");
                privateKey = (RSAPrivateCrtKey) rsa.generatePrivate(
                        new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
                publicKey = (RSAPublicKey) rsa.generatePublic(
                        new RSAPublicKeySpec(privateKey.getModulus(), privateKey.getPublicExponent()));
            }
            if (publicKey.getModulus().bitLength() < MIN_BITS) {
                throw new IllegalArgumentException("ledgerforge.oauth.signing-key must be at least " + MIN_BITS + " bits");
            }
            RSAKey key = new RSAKey.Builder(publicKey).privateKey(privateKey)
                    .keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256).build();
            // Key id = the key's own thumbprint, so verifiers can pick the right key during rotation.
            return new RSAKey.Builder(key).keyID(key.computeThumbprint().toString()).build();
        } catch (GeneralSecurityException | JOSEException e) {
            throw new IllegalStateException("Could not load the OAuth signing key", e);
        }
    }
}

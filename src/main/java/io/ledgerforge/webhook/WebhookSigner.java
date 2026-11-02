package io.ledgerforge.webhook;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Signatures in the Standard Webhooks format (https://www.standardwebhooks.com), so receivers can verify
 * them with existing libraries: {@code webhook-signature: v1,base64(HMAC-SHA256(key, id + "." + timestamp + "." + body))}
 * where the key is the base64 part of the "whsec_" secret. The timestamp lets receivers reject replays.
 */
public final class WebhookSigner {

    public static final String SECRET_PREFIX = "whsec_";
    private static final SecureRandom RANDOM = new SecureRandom();

    private WebhookSigner() {
    }

    public static String newSecret() {
        byte[] key = new byte[32];
        RANDOM.nextBytes(key);
        return SECRET_PREFIX + Base64.getEncoder().encodeToString(key);
    }

    public static String sign(String secret, String messageId, long timestampSeconds, String body) {
        if (!secret.startsWith(SECRET_PREFIX)) {
            throw new IllegalArgumentException("Webhook secrets start with " + SECRET_PREFIX);
        }
        byte[] key = Base64.getDecoder().decode(secret.substring(SECRET_PREFIX.length()));
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] signature = mac.doFinal((messageId + "." + timestampSeconds + "." + body)
                    .getBytes(StandardCharsets.UTF_8));
            return "v1," + Base64.getEncoder().encodeToString(signature);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Constant-time check of one signature, for receivers written in Java (and for tests). */
    public static boolean verify(String secret, String messageId, long timestampSeconds, String body, String header) {
        byte[] expected = sign(secret, messageId, timestampSeconds, body).getBytes(StandardCharsets.US_ASCII);
        for (String candidate : header.split(" ")) {
            if (MessageDigest.isEqual(expected, candidate.getBytes(StandardCharsets.US_ASCII))) {
                return true;
            }
        }
        return false;
    }
}

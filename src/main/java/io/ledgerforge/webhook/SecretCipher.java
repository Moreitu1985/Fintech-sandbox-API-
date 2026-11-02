package io.ledgerforge.webhook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

/**
 * Encrypts endpoint signing secrets at rest with AES-256-GCM. The endpoint id is bound in as associated
 * data, so a ciphertext copied onto another endpoint's row won't decrypt. Format: "v1:" + base64(iv || ct).
 * The secret must be recoverable (it signs every delivery), so it can't be hashed like an API key.
 */
@Component
public class SecretCipher {

    private static final Logger log = LoggerFactory.getLogger(SecretCipher.class);
    private static final String PREFIX = "v1:";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public SecretCipher(WebhookProperties properties) {
        this.key = loadKey(properties);
    }

    static SecretKey loadKey(WebhookProperties properties) {
        String configured = properties.encryptionKey();
        if (configured == null || configured.isBlank()) {
            if (properties.requireEncryptionKey()) {
                throw new IllegalStateException("ledgerforge.webhooks.encryption-key is required (set "
                        + "LEDGERFORGE_WEBHOOKS_ENCRYPTION_KEY to a base64 32-byte key, e.g. `openssl rand -base64 32`)");
            }
            log.warn("No webhook encryption key configured: using a temporary one. Stored webhook secrets "
                    + "will be unreadable after a restart. Set ledgerforge.webhooks.encryption-key outside development.");
            byte[] bytes = new byte[32];
            new SecureRandom().nextBytes(bytes);
            return new SecretKeySpec(bytes, "AES");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(configured.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("ledgerforge.webhooks.encryption-key must be base64", e);
        }
        if (bytes.length != 32) {
            throw new IllegalStateException("ledgerforge.webhooks.encryption-key must decode to exactly 32 bytes");
        }
        return new SecretKeySpec(bytes, "AES");
    }

    public String encrypt(UUID endpointId, String plaintext) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(endpointId.toString().getBytes(StandardCharsets.UTF_8));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return PREFIX + Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + ct.length)
                    .put(iv).put(ct).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not encrypt a webhook secret", e);
        }
    }

    /** @throws IllegalStateException if the ciphertext was altered, belongs to another endpoint or another key */
    public String decrypt(UUID endpointId, String stored) {
        if (stored == null || !stored.startsWith(PREFIX)) {
            throw new IllegalStateException("Unknown webhook secret format");
        }
        try {
            byte[] all = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
            cipher.updateAAD(endpointId.toString().getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(all, IV_BYTES, all.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Could not decrypt a webhook secret", e);
        }
    }
}

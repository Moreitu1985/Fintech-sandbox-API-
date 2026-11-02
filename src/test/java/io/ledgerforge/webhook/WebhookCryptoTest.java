package io.ledgerforge.webhook;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebhookCryptoTest {

    private static WebhookProperties props(String key, boolean require) {
        return new WebhookProperties(key, require, false, true, Duration.ofSeconds(1),
                List.of(Duration.ofSeconds(30)), Duration.ofSeconds(3), Duration.ofSeconds(10), 5, 10, 16);
    }

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    @Test
    void secretsRoundTripAndAreBoundToTheirEndpoint() {
        SecretCipher cipher = new SecretCipher(props(KEY, true));
        UUID endpoint = UUID.randomUUID();
        String stored = cipher.encrypt(endpoint, "whsec_abc");

        assertThat(stored).startsWith("v1:").doesNotContain("whsec_abc");
        assertThat(cipher.decrypt(endpoint, stored)).isEqualTo("whsec_abc");
        assertThat(cipher.encrypt(endpoint, "whsec_abc")).as("random IV").isNotEqualTo(stored);
        // copied onto another endpoint's row: refuses to decrypt
        assertThatThrownBy(() -> cipher.decrypt(UUID.randomUUID(), stored)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void tamperedCiphertextOrWrongKeyIsRejected() {
        SecretCipher cipher = new SecretCipher(props(KEY, true));
        UUID endpoint = UUID.randomUUID();
        String stored = cipher.encrypt(endpoint, "whsec_abc");
        byte[] raw = Base64.getDecoder().decode(stored.substring(3));
        raw[raw.length - 1] ^= 1;
        String tampered = "v1:" + Base64.getEncoder().encodeToString(raw);
        assertThatThrownBy(() -> cipher.decrypt(endpoint, tampered)).isInstanceOf(IllegalStateException.class);

        byte[] otherKey = new byte[32];
        otherKey[0] = 1;
        SecretCipher other = new SecretCipher(props(Base64.getEncoder().encodeToString(otherKey), true));
        assertThatThrownBy(() -> other.decrypt(endpoint, stored)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void productionRefusesToStartWithoutAProperKey() {
        assertThatThrownBy(() -> new SecretCipher(props("", true))).hasMessageContaining("required");
        assertThatThrownBy(() -> new SecretCipher(props("c2hvcnQ=", true))).hasMessageContaining("32 bytes");
        assertThatThrownBy(() -> new SecretCipher(props("not base64!", true))).hasMessageContaining("base64");
        assertThat(new SecretCipher(props("", false))).as("development: temporary key").isNotNull();
    }

    @Test
    void signaturesFollowTheStandardWebhooksFormat() {
        // Test vector from the Standard Webhooks specification's reference libraries.
        String secret = "whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw";
        String id = "msg_p5jXN8AQM9LWM0D4loKWxJek";
        long ts = 1614265330L;
        String body = "{\"test\": 2432232314}";
        String signature = WebhookSigner.sign(secret, id, ts, body);

        assertThat(signature).isEqualTo("v1,g0hM9SsE+OTPJTGt/tmIKtSyZlE3uFJELVlNIOLJ1OE=");
        assertThat(WebhookSigner.verify(secret, id, ts, body, "v1,bogus " + signature)).isTrue();
        assertThat(WebhookSigner.verify(secret, id, ts + 1, body, signature)).isFalse();
        assertThat(WebhookSigner.verify(secret, id, ts, body + " ", signature)).isFalse();
    }

    @Test
    void newSecretsAreRandomAndWellFormed() {
        String a = WebhookSigner.newSecret();
        assertThat(a).startsWith("whsec_");
        assertThat(Base64.getDecoder().decode(a.substring(6))).hasSize(32);
        assertThat(WebhookSigner.newSecret()).isNotEqualTo(a);
    }
}

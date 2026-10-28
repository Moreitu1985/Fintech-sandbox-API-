package io.ledgerforge.security;

import io.ledgerforge.common.Hashing;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * API key format: lf_test_&lt;12 hex prefix&gt;_&lt;64 hex secret&gt;.
 * The prefix is a public lookup handle; only the SHA-256 of the whole key is stored.
 */
public final class ApiKeys {

    public static final String PREFIX = "lf_test_";
    private static final Pattern FORMAT = Pattern.compile("^lf_test_([0-9a-f]{12})_([0-9a-f]{64})$");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();

    private ApiKeys() {
    }

    public record Generated(String plaintext, String lookupPrefix, String hash) {
    }

    public record Parsed(String lookupPrefix, String plaintext) {
    }

    public static Generated generate() {
        byte[] prefixBytes = new byte[6];
        byte[] secretBytes = new byte[32]; // 256 bits of entropy
        RANDOM.nextBytes(prefixBytes);
        RANDOM.nextBytes(secretBytes);
        String prefix = HEX.formatHex(prefixBytes);
        String plaintext = PREFIX + prefix + "_" + HEX.formatHex(secretBytes);
        return new Generated(plaintext, prefix, hash(plaintext));
    }

    public static Optional<Parsed> parse(String raw) {
        if (raw == null || raw.length() > 100) {
            return Optional.empty();
        }
        Matcher m = FORMAT.matcher(raw);
        return m.matches() ? Optional.of(new Parsed(m.group(1), raw)) : Optional.empty();
    }

    /** SHA-256 is appropriate here because keys are random and high-entropy (unlike passwords). */
    public static String hash(String plaintext) {
        return Hashing.sha256Hex(plaintext);
    }
}

package io.ledgerforge.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeysTest {

    @Test
    void generatedKeyParsesAndHashMatches() {
        var key = ApiKeys.generate();
        assertThat(key.plaintext()).startsWith("lf_test_");
        var parsed = ApiKeys.parse(key.plaintext());
        assertThat(parsed).isPresent();
        assertThat(parsed.get().lookupPrefix()).isEqualTo(key.lookupPrefix());
        assertThat(ApiKeys.hash(key.plaintext())).isEqualTo(key.hash());
        assertThat(key.hash()).doesNotContain(key.plaintext());
    }

    @Test
    void keysAreUnique() {
        assertThat(ApiKeys.generate().plaintext()).isNotEqualTo(ApiKeys.generate().plaintext());
    }

    @Test
    void malformedKeysAreRejected() {
        assertThat(ApiKeys.parse(null)).isEmpty();
        assertThat(ApiKeys.parse("")).isEmpty();
        assertThat(ApiKeys.parse("lf_test_abc")).isEmpty();
        assertThat(ApiKeys.parse("lf_live_" + "a".repeat(12) + "_" + "b".repeat(64))).isEmpty();
        assertThat(ApiKeys.parse("lf_test_" + "A".repeat(12) + "_" + "b".repeat(64))).isEmpty();
        assertThat(ApiKeys.parse("lf_test_" + "a".repeat(12) + "_" + "b".repeat(64) + "x")).isEmpty();
    }
}

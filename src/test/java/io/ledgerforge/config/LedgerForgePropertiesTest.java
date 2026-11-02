package io.ledgerforge.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LedgerForgePropertiesTest {

    @Test
    void rateLimitSettingsThatWouldLockClientsOutAreRejected() {
        assertThatThrownBy(() -> new LedgerForgeProperties.RateLimit(0, 10, LedgerForgeProperties.Store.MEMORY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LedgerForgeProperties.RateLimit(10, 0, LedgerForgeProperties.Store.REDIS))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LedgerForgeProperties.RateLimit(10, Double.NaN, LedgerForgeProperties.Store.REDIS))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatNoException()
                .isThrownBy(() -> new LedgerForgeProperties.RateLimit(1, 0.01, LedgerForgeProperties.Store.REDIS));
    }

    @Test
    void weakOrSharedSecretsAreRejected() {
        var ok = new LedgerForgeProperties.RateLimit(100, 50, LedgerForgeProperties.Store.MEMORY);
        String strong = "x".repeat(32);
        assertThatThrownBy(() -> new LedgerForgeProperties("short", null, ok))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LedgerForgeProperties(strong, "short", ok))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LedgerForgeProperties(strong, strong, ok))
                .hasMessageContaining("must differ");
    }
}

package io.ledgerforge.oauth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TokenServiceUnitTest {

    @Test
    void scopesAreNarrowedToWhatWasAskedAndUnknownOnesRefused() {
        assertThat(TokenService.grantedScopes(null)).containsExactly("ledger:read", "ledger:write", "assistant");
        assertThat(TokenService.grantedScopes("  assistant   ledger:read ")).containsExactly("ledger:read", "assistant");
        assertThatThrownBy(() -> TokenService.grantedScopes("ledger:read root")).isInstanceOf(TokenService.OAuthError.class);
    }

    @Test
    void signingKeyMustBePkcs8() {
        assertThatThrownBy(() -> SigningKeys.load("-----BEGIN RSA PRIVATE KEY-----\nabc\n-----END RSA PRIVATE KEY-----"))
                .hasMessageContaining("PKCS#8");
    }
}

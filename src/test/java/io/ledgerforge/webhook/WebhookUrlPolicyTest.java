package io.ledgerforge.webhook;

import io.ledgerforge.common.error.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Server-side request forgery guard, with name resolution stubbed so no real DNS is needed. */
class WebhookUrlPolicyTest {

    private static final Map<String, String> DNS = Map.of(
            "hooks.example.com", "93.184.216.34",
            "rebind.example.com", "10.0.0.5",
            "metadata.example.com", "169.254.169.254",
            "v6.example.com", "2606:4700::6810:85e5",
            "v6-private.example.com", "fd00:ec2::254");

    private final WebhookUrlPolicy strict = new WebhookUrlPolicy(false, host -> {
        String ip = DNS.get(host);
        if (ip == null) {
            if (host.matches("[0-9.]+") || host.contains(":")) {
                return new InetAddress[] {InetAddress.getByName(host)};
            }
            throw new UnknownHostException(host);
        }
        return new InetAddress[] {InetAddress.getByName(ip)};
    });

    @ParameterizedTest
    @ValueSource(strings = {
            "https://hooks.example.com/ledger",
            "https://hooks.example.com:443/ledger?x=1",
            "https://93.184.216.34/hook",
            "https://v6.example.com/hook",
            "https://[2606:4700::6810:85e5]/hook"})
    void publicHttpsTargetsAreAllowed(String url) {
        assertThat(strict.validate(url)).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://hooks.example.com/hook",            // not https
            "https://hooks.example.com:8443/hook",      // non-standard port
            "https://user:pass@hooks.example.com/hook", // credentials in the URL
            "https://hooks.example.com/hook#frag",
            "ftp://hooks.example.com/hook",
            "hooks.example.com/hook",                   // not absolute
            "https:///nohost",
            "https://unknown.example.com/hook",         // doesn't resolve
            "https://rebind.example.com/hook",          // public name, private address
            "https://metadata.example.com/latest",      // cloud metadata service
            "https://v6-private.example.com/hook",      // IPv6 unique-local (AWS metadata over IPv6)
            "https://127.0.0.1/hook",
            "https://10.1.2.3/hook",
            "https://172.16.0.1/hook",
            "https://172.31.255.255/hook",
            "https://192.168.1.1/hook",
            "https://169.254.169.254/latest/meta-data",
            "https://100.64.0.1/hook",                  // carrier-grade NAT
            "https://0.0.0.0/hook",
            "https://198.18.0.1/hook",
            "https://224.0.0.1/hook",
            "https://255.255.255.255/hook",
            "https://[::1]/hook",
            "https://[::ffff:127.0.0.1]/hook",          // IPv4-mapped loopback
            "https://[::ffff:a9fe:a9fe]/hook",          // IPv4-mapped 169.254.169.254
            "https://[fe80::1]/hook",
            "https://[fc00::1]/hook",
            "https://[2001:db8::1]/hook"})
    void privateOrUnsafeTargetsAreRefused(String url) {
        assertThatThrownBy(() -> strict.validate(url))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code()).isEqualTo(WebhookUrlPolicy.CODE);
    }

    @Test
    void overlongUrlsAreRefused() {
        assertThatThrownBy(() -> strict.validate("https://hooks.example.com/" + "a".repeat(2100)))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void developmentModeAllowsLocalReceivers() {
        WebhookUrlPolicy dev = new WebhookUrlPolicy(true, host -> new InetAddress[0]);
        assertThat(dev.validate("http://127.0.0.1:9000/hook")).isNotNull();
        // ...but still never credentials or non-http schemes
        assertThatThrownBy(() -> dev.validate("http://u:p@127.0.0.1/hook")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> dev.validate("file:///etc/passwd")).isInstanceOf(ApiException.class);
    }
}

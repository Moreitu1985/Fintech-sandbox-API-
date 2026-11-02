package io.ledgerforge.webhook;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The send step on its own: the address is re-checked at send time, and redirects are never followed. */
class WebhookDispatcherUnitTest {

    private static WebhookProperties props(boolean insecure) {
        return new WebhookProperties("", false, insecure, false, Duration.ofSeconds(1),
                List.of(Duration.ofSeconds(1)), Duration.ofSeconds(2), Duration.ofSeconds(3), 5, 10, 4, 2, 4);
    }

    private static WebhookDeliveryRecorder.Attempt attempt(String url) {
        return new WebhookDeliveryRecorder.Attempt(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), url,
                WebhookSigner.newSecret(), "{}", 1);
    }

    @Test
    void aHostThatNowResolvesPrivatelyIsNotCalled() throws Exception {
        try (FakeReceiver receiver = new FakeReceiver()) {
            // Registered while public; by send time the name points inside the network (DNS rebinding).
            WebhookUrlPolicy strict = new WebhookUrlPolicy(false,
                    host -> new InetAddress[] {InetAddress.getByName("127.0.0.1")});
            WebhookDispatcher dispatcher = new WebhookDispatcher(null, null, strict, props(false));
            WebhookDispatcher.Result result = dispatcher.send(attempt("https://hooks.example.com/hook"));
            assertThat(result.error()).isEqualTo("url_not_allowed");
            assertThat(result.statusCode()).isNull();
            assertThat(receiver.requests).isEmpty();
        }
    }

    @Test
    void redirectsAreFailuresNotFollowed() throws Exception {
        try (FakeReceiver receiver = new FakeReceiver()) {
            receiver.respondAlways(302);
            WebhookDispatcher dispatcher = new WebhookDispatcher(null, null,
                    new WebhookUrlPolicy(true, host -> new InetAddress[0]), props(true));
            WebhookDispatcher.Result result = dispatcher.send(attempt(receiver.url()));
            assertThat(result.statusCode()).isEqualTo(302);
            assertThat(result.error()).isEqualTo("http_302");
            assertThat(receiver.requests).hasSize(1);
        }
    }

    @Test
    void secretsNeverAppearInAttemptDescriptions() {
        WebhookDeliveryRecorder.Attempt a = attempt("https://hooks.example.com/hook");
        assertThat(a.toString()).doesNotContain(a.secret()).doesNotContain("whsec_");
    }
}

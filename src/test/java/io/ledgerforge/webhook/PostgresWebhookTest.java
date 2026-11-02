package io.ledgerforge.webhook;

import io.ledgerforge.postgres.PostgresTest;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/** Every test in {@link WebhookTest} on real PostgreSQL, plus the multi-instance claim race. */
@PostgresTest
class PostgresWebhookTest extends WebhookTest {

    @Test
    void exactlyOneInstanceWinsTheClaimOnADelivery() throws Exception {
        Tenant t = newTenant();
        Instant future = Instant.now().plus(Duration.ofHours(1)).truncatedTo(ChronoUnit.MICROS);
        UUID deliveryId = tx.execute(s -> {
            UUID endpointId = UUID.randomUUID();
            endpoints.save(new WebhookEndpoint(endpointId, t.id(), "https://hooks.example.com/x",
                    List.of("transfer.posted"), null, "v1:unused"));
            UUID eventId = UUID.randomUUID();
            events.save(new WebhookEvent(eventId, t.id(), "transfer.posted", "{}", Instant.now()));
            // Not due for an hour, so the running dispatcher leaves it alone.
            WebhookDelivery d = new WebhookDelivery(t.id(), endpointId, eventId, future);
            return deliveryRows.save(d).getId();
        });
        Instant seen = deliveryRows.findById(deliveryId).orElseThrow().getNextAttemptAt();

        int racers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < racers; i++) {
            Callable<Boolean> claim = () -> {
                start.await();
                return recorder.claim(deliveryId, seen, seen.plus(Duration.ofMinutes(2)));
            };
            results.add(pool.submit(claim));
        }
        start.countDown();
        int winners = 0;
        for (Future<Boolean> r : results) {
            winners += r.get() ? 1 : 0;
        }
        pool.shutdown();
        assertThat(winners).isEqualTo(1);
    }
}

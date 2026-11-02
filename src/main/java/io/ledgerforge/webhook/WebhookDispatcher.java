package io.ledgerforge.webhook;

import io.ledgerforge.common.error.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.domain.PageRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Sends due deliveries. Every instance runs one; a compare-and-set claim with a lease makes sure each
 * delivery is in flight on one instance at a time, and is picked up again if that instance dies.
 *
 * <p>Delivery is at-least-once: a receiver may see the same event twice (e.g. it answered too slowly), so
 * receivers should de-duplicate on the {@code webhook-id} header, which is stable across retries.
 */
@Component
public class WebhookDispatcher implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(WebhookDispatcher.class);
    static final String USER_AGENT = "LedgerForge-Webhooks/1.0";

    private final WebhookDeliveryRepository deliveries;
    private final WebhookDeliveryRecorder recorder;
    private final WebhookUrlPolicy urlPolicy;
    private final WebhookProperties properties;
    private final HttpClient http;
    private final Semaphore slots;
    private final Map<UUID, Integer> perEndpoint = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> perTenant = new ConcurrentHashMap<>();
    private ScheduledExecutorService scheduler;
    private ExecutorService senders;
    private volatile boolean running;

    public WebhookDispatcher(WebhookDeliveryRepository deliveries, WebhookDeliveryRecorder recorder,
                             WebhookUrlPolicy urlPolicy, WebhookProperties properties) {
        this.deliveries = deliveries;
        this.recorder = recorder;
        this.urlPolicy = urlPolicy;
        this.properties = properties;
        this.slots = new Semaphore(properties.maxConcurrentDeliveries());
        this.http = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER) // a redirect could point anywhere, including inside
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    @Override
    public void start() {
        if (!properties.dispatcherEnabled()) {
            return;
        }
        senders = Executors.newVirtualThreadPerTaskExecutor();
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "webhook-dispatcher");
            t.setDaemon(true);
            return t;
        });
        long interval = properties.pollInterval().toMillis();
        scheduler.scheduleWithFixedDelay(this::pollSafely, interval, interval, TimeUnit.MILLISECONDS);
        running = true;
    }

    @Override
    public void stop() {
        running = false;
        if (scheduler != null) {
            scheduler.shutdown(); // let a poll in progress finish claiming and handing over its work
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (senders != null) {
            senders.shutdown();
            try {
                senders.awaitTermination(properties.requestTimeout().toSeconds() + 5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void pollSafely() {
        try {
            poll();
        } catch (RuntimeException e) {
            log.warn("Webhook dispatcher poll failed: {}", e.toString());
        }
    }

    /**
     * One round. Slots are shared fairly: endpoints are visited longest-waiting first, and none may hold
     * more than {@code max-in-flight-per-endpoint} sends (nor a tenant more than {@code -per-tenant}), so a
     * slow or dead receiver can't take every slot and delay other tenants' webhooks.
     */
    void poll() {
        if (!running || slots.availablePermits() == 0) {
            return;
        }
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant leaseUntil = now.plus(properties.lease());
        List<WebhookDeliveryRepository.DueEndpoint> endpoints =
                deliveries.findDueEndpoints(now, PageRequest.of(0, 200));
        for (WebhookDeliveryRepository.DueEndpoint e : endpoints) {
            int room = Math.min(properties.maxInFlightPerEndpoint() - inFlight(perEndpoint, e.getEndpointId()),
                    properties.maxInFlightPerTenant() - inFlight(perTenant, e.getTenantId()));
            room = Math.min(room, slots.availablePermits());
            if (room <= 0) {
                if (slots.availablePermits() == 0) {
                    return;
                }
                continue;
            }
            for (WebhookDeliveryRepository.Due d : deliveries.findDueFor(e.getEndpointId(), now, PageRequest.of(0, room))) {
                if (!running || !slots.tryAcquire()) {
                    return;
                }
                if (!recorder.claim(d.getId(), d.getNextAttemptAt(), leaseUntil)) {
                    slots.release(); // another instance got it
                    continue;
                }
                track(d, +1);
                try {
                    senders.submit(() -> {
                        try {
                            deliver(d, leaseUntil);
                        } finally {
                            track(d, -1);
                            slots.release();
                        }
                    });
                } catch (RejectedExecutionException shuttingDown) {
                    track(d, -1);
                    slots.release(); // the lease expires and another instance (or the next start) sends it
                    return;
                }
            }
        }
    }

    private static int inFlight(Map<UUID, Integer> counts, UUID key) {
        return counts.getOrDefault(key, 0);
    }

    /** Atomic per key: an entry is removed when it reaches zero, so the maps don't grow forever. */
    private void track(WebhookDeliveryRepository.Due d, int delta) {
        perEndpoint.compute(d.getEndpointId(), (k, v) -> adjust(v, delta));
        perTenant.compute(d.getTenantId(), (k, v) -> adjust(v, delta));
    }

    private static Integer adjust(Integer current, int delta) {
        int n = (current == null ? 0 : current) + delta;
        return n <= 0 ? null : n;
    }

    private void deliver(WebhookDeliveryRepository.Due due, Instant leaseUntil) {
        WebhookDeliveryRecorder.Attempt attempt;
        try {
            attempt = recorder.prepare(due.getId(), leaseUntil).orElse(null);
        } catch (RuntimeException e) {
            log.error("Could not prepare webhook delivery {}", due.getId(), e);
            safeRecord(due, leaseUntil, null, "internal_error");
            return;
        }
        if (attempt == null) {
            return;
        }
        Result result;
        try {
            result = send(attempt);
        } catch (RuntimeException e) {
            // Counted as a failed attempt, so a delivery that always throws still runs out of retries.
            log.error("Webhook delivery {} failed unexpectedly", due.getId(), e);
            result = new Result(null, "internal_error");
        }
        safeRecord(due, leaseUntil, result.statusCode, result.error);
    }

    private void safeRecord(WebhookDeliveryRepository.Due due, Instant leaseUntil, Integer statusCode, String error) {
        try {
            recorder.record(due.getId(), leaseUntil, statusCode, error);
        } catch (ObjectOptimisticLockingFailureException e) {
            // Cancelled or taken over by another instance between our check and commit: theirs stands.
            log.debug("Webhook delivery {} changed while recording; result dropped", due.getId());
        } catch (RuntimeException e) {
            // The lease expires and the delivery is tried again: at-least-once, never lost.
            log.error("Could not record webhook delivery {}", due.getId(), e);
        }
    }

    record Result(Integer statusCode, String error) {
    }

    Result send(WebhookDeliveryRecorder.Attempt attempt) {
        URI uri = URI.create(attempt.url());
        try {
            urlPolicy.checkTarget(uri); // again now: the host's DNS may have changed since registration
        } catch (ApiException e) {
            return new Result(null, "url_not_allowed");
        }
        long timestamp = Instant.now().getEpochSecond();
        String messageId = attempt.eventId().toString();
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(properties.requestTimeout())
                .header("Content-Type", "application/json")
                .header("User-Agent", USER_AGENT)
                .header("webhook-id", messageId)
                .header("webhook-timestamp", Long.toString(timestamp))
                .header("webhook-signature", WebhookSigner.sign(attempt.secret(), messageId, timestamp, attempt.body()))
                .POST(HttpRequest.BodyPublishers.ofString(attempt.body()))
                .build();
        try {
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            // Only the status matters. Close without reading, so a huge or endless body can't hold a sender.
            response.body().close();
            int code = response.statusCode();
            return new Result(code, code >= 200 && code < 300 ? null : "http_" + code);
        } catch (HttpConnectTimeoutException e) {
            return new Result(null, "connect_timeout");
        } catch (HttpTimeoutException e) {
            return new Result(null, "timeout");
        } catch (ConnectException e) {
            return new Result(null, "connection_refused");
        } catch (IOException e) {
            return new Result(null, "network_error");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(null, "interrupted");
        }
    }
}

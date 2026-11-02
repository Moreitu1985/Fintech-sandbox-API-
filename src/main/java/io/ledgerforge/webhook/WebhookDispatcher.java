package io.ledgerforge.webhook;

import io.ledgerforge.common.error.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.domain.PageRequest;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
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
            scheduler.shutdownNow();
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

    /** One round: claim as many due deliveries as there are free send slots, and send them. */
    void poll() {
        int free = slots.availablePermits();
        if (!running || free == 0) {
            return;
        }
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        List<WebhookDeliveryRepository.Due> due = deliveries.findDue(now, PageRequest.of(0, free));
        Instant leaseUntil = now.plus(properties.lease());
        for (WebhookDeliveryRepository.Due d : due) {
            if (!slots.tryAcquire()) {
                return;
            }
            boolean claimed;
            try {
                claimed = recorder.claim(d.getId(), d.getNextAttemptAt(), leaseUntil);
            } catch (RuntimeException e) {
                slots.release();
                throw e;
            }
            if (!claimed) {
                slots.release(); // another instance got it
                continue;
            }
            senders.submit(() -> {
                try {
                    deliver(d);
                } finally {
                    slots.release();
                }
            });
        }
    }

    private void deliver(WebhookDeliveryRepository.Due due) {
        WebhookDeliveryRecorder.Attempt attempt;
        try {
            attempt = recorder.prepare(due.getId()).orElse(null);
        } catch (RuntimeException e) {
            log.error("Could not prepare webhook delivery {}", due.getId(), e);
            safeRecord(due, null, "internal_error");
            return;
        }
        if (attempt == null) {
            return;
        }
        Result result = send(attempt);
        safeRecord(due, result.statusCode, result.error);
    }

    private void safeRecord(WebhookDeliveryRepository.Due due, Integer statusCode, String error) {
        try {
            recorder.record(due.getId(), statusCode, error);
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

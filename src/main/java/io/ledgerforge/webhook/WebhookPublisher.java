package io.ledgerforge.webhook;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Transactional outbox. Called inside the transaction that makes the change (a transfer, a new account),
 * so the event exists if and only if the change committed: a rolled-back transfer never notifies anyone,
 * and a crash right after commit loses nothing, because the dispatcher finds the rows later.
 */
@Component
public class WebhookPublisher {

    private final WebhookEndpointRepository endpoints;
    private final WebhookEventRepository events;
    private final WebhookDeliveryRepository deliveries;
    private final ObjectMapper json;

    public WebhookPublisher(WebhookEndpointRepository endpoints, WebhookEventRepository events,
                            WebhookDeliveryRepository deliveries, ObjectMapper json) {
        this.endpoints = endpoints;
        this.events = events;
        this.deliveries = deliveries;
        this.json = json;
    }

    /** Queues the event for every active endpoint of this tenant that subscribes to it. Cheap when none do. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(UUID tenantId, String eventType, Object data) {
        List<WebhookEndpoint> subscribers = endpoints.findActive(tenantId).stream()
                .filter(e -> e.subscribesTo(eventType))
                .toList();
        if (!subscribers.isEmpty()) {
            enqueue(tenantId, eventType, data, subscribers);
        }
    }

    /** Queues an event for one endpoint regardless of its subscriptions (test events). */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID publishTo(WebhookEndpoint endpoint, String eventType, Object data) {
        return enqueue(endpoint.getTenantId(), eventType, data, List.of(endpoint));
    }

    private UUID enqueue(UUID tenantId, String eventType, Object data, List<WebhookEndpoint> to) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        UUID eventId = UUID.randomUUID();
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("id", eventId);
        envelope.put("type", eventType);
        envelope.put("createdAt", now);
        envelope.put("data", data);
        String body;
        try {
            body = json.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise webhook event " + eventType, e);
        }
        events.save(new WebhookEvent(eventId, tenantId, eventType, body, now));
        for (WebhookEndpoint endpoint : to) {
            deliveries.save(new WebhookDelivery(tenantId, endpoint.getId(), eventId, now));
        }
        return eventId;
    }
}

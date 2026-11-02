package io.ledgerforge.webhook;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class WebhookDtos {

    private WebhookDtos() {
    }

    public record CreateEndpointRequest(
            @NotBlank @Size(max = 2048) String url,
            @NotEmpty @Size(max = 10) List<@NotBlank String> eventTypes,
            @Size(max = 140) @Pattern(regexp = "^[^\\p{Cntrl}]*$") String description) {
    }

    public record EndpointResponse(UUID id, String url, List<String> eventTypes, String description,
                                   boolean enabled, int failedDeliveriesInRow, Instant createdAt,
                                   Instant disabledAt) {
        static EndpointResponse from(WebhookEndpoint e) {
            return new EndpointResponse(e.getId(), e.getUrl(), e.eventTypeList(), e.getDescription(),
                    e.isEnabled(), e.getFailedDeliveriesInRow(), e.getCreatedAt(), e.getDisabledAt());
        }
    }

    /** Returned only when an endpoint is created or its secret rotated: the secret is never shown again. */
    public record EndpointWithSecret(EndpointResponse endpoint, String secret) {
    }

    public record DeliveryResponse(UUID id, UUID endpointId, UUID eventId, String status, int attempts,
                                   Instant nextAttemptAt, Instant lastAttemptAt, Integer lastStatusCode,
                                   String lastError, Instant createdAt) {
        static DeliveryResponse from(WebhookDelivery d) {
            boolean pending = d.getStatus() == WebhookDelivery.Status.PENDING;
            return new DeliveryResponse(d.getId(), d.getEndpointId(), d.getEventId(), d.getStatus().name(),
                    d.getAttempts(), pending ? d.getNextAttemptAt() : null, d.getLastAttemptAt(),
                    d.getLastStatusCode(), d.getLastError(), d.getCreatedAt());
        }
    }

    public record TestEventResponse(UUID eventId) {
    }
}

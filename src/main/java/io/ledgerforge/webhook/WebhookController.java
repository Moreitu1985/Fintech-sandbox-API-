package io.ledgerforge.webhook;

import io.ledgerforge.ledger.dto.LedgerDtos.PageResponse;
import io.ledgerforge.security.TenantPrincipal;
import io.ledgerforge.webhook.WebhookDtos.CreateEndpointRequest;
import io.ledgerforge.webhook.WebhookDtos.DeliveryResponse;
import io.ledgerforge.webhook.WebhookDtos.EndpointResponse;
import io.ledgerforge.webhook.WebhookDtos.EndpointWithSecret;
import io.ledgerforge.webhook.WebhookDtos.TestEventResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/** Webhook endpoints, scoped to the caller's tenant. Reads need ledger:read, changes ledger:write. */
@Tag(name = "Webhooks")
@RestController
@RequestMapping("/v1/webhooks")
public class WebhookController {

    private final WebhookService webhooks;

    public WebhookController(WebhookService webhooks) {
        this.webhooks = webhooks;
    }

    @Operation(summary = "Register an endpoint",
            description = "Returns the signing secret once. Events are signed in the Standard Webhooks format.")
    @PostMapping
    public ResponseEntity<EndpointWithSecret> create(@AuthenticationPrincipal TenantPrincipal caller,
                                                     @Valid @RequestBody CreateEndpointRequest request) {
        EndpointWithSecret created = webhooks.create(caller.tenantId(), request);
        return ResponseEntity.created(URI.create("/v1/webhooks/" + created.endpoint().id())).body(created);
    }

    @Operation(summary = "List endpoints")
    @GetMapping
    public List<EndpointResponse> list(@AuthenticationPrincipal TenantPrincipal caller) {
        return webhooks.list(caller.tenantId()).stream().map(EndpointResponse::from).toList();
    }

    @Operation(summary = "Get an endpoint")
    @GetMapping("/{endpointId}")
    public EndpointResponse get(@AuthenticationPrincipal TenantPrincipal caller, @PathVariable UUID endpointId) {
        return EndpointResponse.from(webhooks.get(caller.tenantId(), endpointId));
    }

    @Operation(summary = "Delete an endpoint", description = "Pending deliveries to it are cancelled.")
    @DeleteMapping("/{endpointId}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal TenantPrincipal caller, @PathVariable UUID endpointId) {
        webhooks.delete(caller.tenantId(), endpointId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Rotate the signing secret", description = "The old secret stops working immediately.")
    @PostMapping("/{endpointId}/rotate-secret")
    public EndpointWithSecret rotate(@AuthenticationPrincipal TenantPrincipal caller, @PathVariable UUID endpointId) {
        return webhooks.rotateSecret(caller.tenantId(), endpointId);
    }

    @Operation(summary = "Re-enable an endpoint that was disabled after repeated failures")
    @PostMapping("/{endpointId}/enable")
    public EndpointResponse enable(@AuthenticationPrincipal TenantPrincipal caller, @PathVariable UUID endpointId) {
        return EndpointResponse.from(webhooks.enable(caller.tenantId(), endpointId));
    }

    @Operation(summary = "Send a webhook.test event to this endpoint")
    @PostMapping("/{endpointId}/test")
    public ResponseEntity<TestEventResponse> test(@AuthenticationPrincipal TenantPrincipal caller,
                                                  @PathVariable UUID endpointId) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new TestEventResponse(webhooks.sendTest(caller.tenantId(), endpointId)));
    }

    @Operation(summary = "Delivery history for an endpoint, newest first")
    @GetMapping("/{endpointId}/deliveries")
    public PageResponse<DeliveryResponse> deliveries(@AuthenticationPrincipal TenantPrincipal caller,
                                                     @PathVariable UUID endpointId,
                                                     @RequestParam(defaultValue = "0") int page,
                                                     @RequestParam(defaultValue = "20") int size) {
        return PageResponse.of(webhooks.deliveries(caller.tenantId(), endpointId, page, size), DeliveryResponse::from);
    }

    @Operation(summary = "Retry a failed or cancelled delivery")
    @PostMapping("/deliveries/{deliveryId}/retry")
    public DeliveryResponse retry(@AuthenticationPrincipal TenantPrincipal caller, @PathVariable UUID deliveryId) {
        return DeliveryResponse.from(webhooks.retry(caller.tenantId(), deliveryId));
    }
}

package io.ledgerforge.webhook;

import io.ledgerforge.audit.AuditService;
import io.ledgerforge.common.error.ApiException;
import io.ledgerforge.tenant.TenantRepository;
import io.ledgerforge.webhook.WebhookDtos.CreateEndpointRequest;
import io.ledgerforge.webhook.WebhookDtos.EndpointWithSecret;
import io.ledgerforge.webhook.WebhookDtos.EndpointResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Endpoint management. Every change locks the tenant first (the platform-wide lock order) and is audited. */
@Service
public class WebhookService {

    private final WebhookEndpointRepository endpoints;
    private final WebhookDeliveryRepository deliveries;
    private final WebhookPublisher publisher;
    private final WebhookUrlPolicy urlPolicy;
    private final SecretCipher cipher;
    private final WebhookProperties properties;
    private final TenantRepository tenants;
    private final AuditService audit;

    public WebhookService(WebhookEndpointRepository endpoints, WebhookDeliveryRepository deliveries,
                          WebhookPublisher publisher, WebhookUrlPolicy urlPolicy, SecretCipher cipher,
                          WebhookProperties properties, TenantRepository tenants, AuditService audit) {
        this.endpoints = endpoints;
        this.deliveries = deliveries;
        this.publisher = publisher;
        this.urlPolicy = urlPolicy;
        this.cipher = cipher;
        this.properties = properties;
        this.tenants = tenants;
        this.audit = audit;
    }

    @Transactional
    public EndpointWithSecret create(UUID tenantId, CreateEndpointRequest request) {
        List<String> types = List.copyOf(new LinkedHashSet<>(request.eventTypes()));
        for (String type : types) {
            if (!WebhookEventTypes.SUBSCRIBABLE.contains(type)) {
                throw ApiException.badRequest("UNKNOWN_EVENT_TYPE",
                        "Unknown event type '" + type + "'. Supported: " + WebhookEventTypes.SUBSCRIBABLE);
            }
        }
        URI url = urlPolicy.validate(request.url()); // resolves DNS: done before taking the tenant lock
        lockTenant(tenantId);
        if (endpoints.findLive(tenantId).size() >= properties.maxEndpointsPerTenant()) {
            throw ApiException.unprocessable("WEBHOOK_LIMIT_REACHED",
                    "A tenant can have at most " + properties.maxEndpointsPerTenant() + " webhook endpoints.");
        }
        UUID id = UUID.randomUUID();
        String secret = WebhookSigner.newSecret();
        String description = request.description() == null ? null : request.description().strip();
        WebhookEndpoint endpoint = endpoints.save(new WebhookEndpoint(id, tenantId, url.toString(), types,
                description, cipher.encrypt(id, secret)));
        audit.append(tenantId, "WEBHOOK_ENDPOINT_CREATED", "endpointId=" + id + ";url=" + url.toString()
                + ";events=" + String.join(",", types));
        return new EndpointWithSecret(EndpointResponse.from(endpoint), secret);
    }

    @Transactional(readOnly = true)
    public List<WebhookEndpoint> list(UUID tenantId) {
        return endpoints.findLive(tenantId);
    }

    @Transactional(readOnly = true)
    public WebhookEndpoint get(UUID tenantId, UUID endpointId) {
        return find(tenantId, endpointId);
    }

    @Transactional
    public void delete(UUID tenantId, UUID endpointId) {
        lockTenant(tenantId);
        WebhookEndpoint endpoint = find(tenantId, endpointId);
        endpoint.delete();
        deliveries.cancelPending(endpointId, "endpoint_deleted");
        audit.append(tenantId, "WEBHOOK_ENDPOINT_DELETED", "endpointId=" + endpointId);
    }

    /** The old secret stops working at once; deliveries already queued are signed with the new one. */
    @Transactional
    public EndpointWithSecret rotateSecret(UUID tenantId, UUID endpointId) {
        lockTenant(tenantId);
        WebhookEndpoint endpoint = find(tenantId, endpointId);
        String secret = WebhookSigner.newSecret();
        endpoint.rotateSecret(cipher.encrypt(endpointId, secret));
        audit.append(tenantId, "WEBHOOK_SECRET_ROTATED", "endpointId=" + endpointId);
        return new EndpointWithSecret(EndpointResponse.from(endpoint), secret);
    }

    @Transactional
    public WebhookEndpoint enable(UUID tenantId, UUID endpointId) {
        lockTenant(tenantId);
        WebhookEndpoint endpoint = find(tenantId, endpointId);
        if (!endpoint.isEnabled()) {
            endpoint.enable();
            audit.append(tenantId, "WEBHOOK_ENDPOINT_ENABLED", "endpointId=" + endpointId);
        }
        return endpoint;
    }

    @Transactional
    public UUID sendTest(UUID tenantId, UUID endpointId) {
        lockTenant(tenantId);
        WebhookEndpoint endpoint = find(tenantId, endpointId);
        if (!endpoint.isEnabled()) {
            throw ApiException.unprocessable("WEBHOOK_ENDPOINT_DISABLED", "Enable the endpoint before testing it.");
        }
        return publisher.publishTo(endpoint, WebhookEventTypes.TEST,
                Map.of("message", "Test event from LedgerForge", "endpointId", endpointId));
    }

    @Transactional(readOnly = true)
    public Page<WebhookDelivery> deliveries(UUID tenantId, UUID endpointId, int page, int size) {
        find(tenantId, endpointId);
        return deliveries.findForEndpoint(endpointId, tenantId, PageRequest.of(Math.max(page, 0),
                Math.min(Math.max(size, 1), 100), Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))));
    }

    /** Queues a delivery that gave up (or was cancelled) again, from attempt one. */
    @Transactional
    public WebhookDelivery retry(UUID tenantId, UUID deliveryId) {
        lockTenant(tenantId);
        WebhookDelivery delivery = deliveries.findByIdAndTenantId(deliveryId, tenantId)
                .orElseThrow(() -> ApiException.notFound("Delivery"));
        WebhookEndpoint endpoint = find(tenantId, delivery.getEndpointId());
        if (delivery.getStatus() == WebhookDelivery.Status.PENDING
                || delivery.getStatus() == WebhookDelivery.Status.SUCCEEDED) {
            throw ApiException.unprocessable("DELIVERY_NOT_RETRYABLE",
                    "Only failed or cancelled deliveries can be retried.");
        }
        if (!endpoint.isEnabled()) {
            throw ApiException.unprocessable("WEBHOOK_ENDPOINT_DISABLED", "Enable the endpoint before retrying.");
        }
        delivery.requeue(Instant.now());
        return delivery;
    }

    private WebhookEndpoint find(UUID tenantId, UUID endpointId) {
        return endpoints.findLive(endpointId, tenantId).orElseThrow(() -> ApiException.notFound("Webhook endpoint"));
    }

    private void lockTenant(UUID tenantId) {
        tenants.lockById(tenantId).orElseThrow(() -> ApiException.notFound("Tenant"));
    }
}

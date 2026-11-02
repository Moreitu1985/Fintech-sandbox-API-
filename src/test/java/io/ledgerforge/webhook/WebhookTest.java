package io.ledgerforge.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import io.ledgerforge.IntegrationTestBase;
import io.ledgerforge.audit.AuditEvent;
import io.ledgerforge.audit.AuditEventRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End to end: register an endpoint, move money, and check what the receiver gets. The test profile
 * polls every 100 ms and retries twice after 200 ms; two give-ups in a row disable an endpoint.
 */
class WebhookTest extends IntegrationTestBase {

    private static final Duration WAIT = Duration.ofSeconds(10);
    private FakeReceiver receiver;

    @Autowired
    WebhookDeliveryRecorder recorder;
    @Autowired
    WebhookEndpointRepository endpoints;
    @Autowired
    WebhookEventRepository events;
    @Autowired
    WebhookDeliveryRepository deliveryRows;
    @Autowired
    AuditEventRepository auditEvents;
    @Autowired
    TransactionTemplate tx;

    @BeforeEach
    void startReceiver() throws Exception {
        receiver = new FakeReceiver();
    }

    @AfterEach
    void stopReceiver() {
        receiver.close();
    }

    private JsonNode register(Tenant t, String url, List<String> events) throws Exception {
        String body = mvc.perform(post("/v1/webhooks").header("Authorization", t.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("url", url, "eventTypes", events,
                                "description", "test receiver"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private JsonNode deliveries(Tenant t, String endpointId) throws Exception {
        return json.readTree(mvc.perform(get("/v1/webhooks/" + endpointId + "/deliveries")
                        .header("Authorization", t.bearer()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("items");
    }

    private JsonNode awaitDeliveryStatus(Tenant t, String endpointId, String wanted) throws Exception {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            JsonNode items = deliveries(t, endpointId);
            if (!items.isEmpty() && items.get(0).get("status").asText().equals(wanted)) {
                return items.get(0);
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Delivery never reached " + wanted + ": " + deliveries(t, endpointId));
    }

    @Test
    void transferIsDeliveredSignedInTheStandardWebhooksFormat() throws Exception {
        Tenant t = newTenant();
        JsonNode created = register(t, receiver.url(), List.of("transfer.posted"));
        String secret = created.get("secret").asText();
        assertThat(secret).startsWith("whsec_");
        assertThat(created.get("endpoint").get("enabled").asBoolean()).isTrue();

        UUID treasury = openAccount(t, "ZAR", true);   // account.created: not subscribed, not sent
        UUID alice = openAccount(t, "ZAR", false);
        transfer(t, newKey(), treasury, alice, 12_345, "ZAR").andExpect(status().isCreated());

        FakeReceiver.Received got = receiver.await(r -> !r.isEmpty(), WAIT).getFirst();
        String id = got.header("webhook-id");
        long ts = Long.parseLong(got.header("webhook-timestamp"));
        assertThat(WebhookSigner.verify(secret, id, ts, got.body(), got.header("webhook-signature"))).isTrue();
        assertThat(Math.abs(Instant.now().getEpochSecond() - ts)).isLessThan(60);
        assertThat(got.header("Content-Type")).isEqualTo("application/json");
        assertThat(got.header("User-Agent")).isEqualTo(WebhookDispatcher.USER_AGENT);

        JsonNode event = json.readTree(got.body());
        assertThat(event.get("id").asText()).isEqualTo(id);
        assertThat(event.get("type").asText()).isEqualTo("transfer.posted");
        assertThat(event.get("data").get("amount").asLong()).isEqualTo(12_345);
        assertThat(event.get("data").get("toAccountId").asText()).isEqualTo(alice.toString());

        JsonNode delivery = awaitDeliveryStatus(t, created.get("endpoint").get("id").asText(), "SUCCEEDED");
        assertThat(delivery.get("attempts").asInt()).isEqualTo(1);
        assertThat(delivery.get("lastStatusCode").asInt()).isEqualTo(200);
        Thread.sleep(400);
        assertThat(receiver.requests).as("only subscribed events, exactly once").hasSize(1);
    }

    @Test
    void secretIsShownOnceAndNeverListed() throws Exception {
        Tenant t = newTenant();
        JsonNode created = register(t, receiver.url(), List.of("account.created"));
        String id = created.get("endpoint").get("id").asText();
        String list = mvc.perform(get("/v1/webhooks").header("Authorization", t.bearer()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String one = mvc.perform(get("/v1/webhooks/" + id).header("Authorization", t.bearer()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(list).contains(id).doesNotContain("whsec_").doesNotContain("secret");
        assertThat(one).doesNotContain("whsec_").doesNotContain("secret");
    }

    @Test
    void failedTransfersSendNothing() throws Exception {
        Tenant t = newTenant();
        UUID alice = openAccount(t, "ZAR", false);
        UUID bob = openAccount(t, "ZAR", false);
        register(t, receiver.url(), List.of("transfer.posted"));
        transfer(t, newKey(), alice, bob, 500, "ZAR").andExpect(status().isUnprocessableEntity()); // no funds
        Thread.sleep(500);
        assertThat(receiver.requests).isEmpty();
    }

    @Test
    void failuresAreRetriedWithTheSameIdAndSignature() throws Exception {
        receiver.respondWith(500, 503);   // then 200
        Tenant t = newTenant();
        JsonNode created = register(t, receiver.url(), List.of("account.created"));
        openAccount(t, "ZAR", false);

        List<FakeReceiver.Received> got = receiver.await(r -> r.size() >= 3, WAIT);
        assertThat(got).extracting(r -> r.header("webhook-id")).containsOnly(got.getFirst().header("webhook-id"));
        assertThat(got).extracting(FakeReceiver.Received::body).containsOnly(got.getFirst().body());
        JsonNode delivery = awaitDeliveryStatus(t, created.get("endpoint").get("id").asText(), "SUCCEEDED");
        assertThat(delivery.get("attempts").asInt()).isEqualTo(3);
    }

    @Test
    void endpointIsDisabledAfterRepeatedGiveUpsAndCanBeReenabled() throws Exception {
        receiver.respondAlways(500);
        Tenant t = newTenant();
        JsonNode created = register(t, receiver.url(), List.of("account.created"));
        String id = created.get("endpoint").get("id").asText();

        openAccount(t, "ZAR", false);
        awaitDeliveryStatus(t, id, "FAILED");
        openAccount(t, "ZAR", false);
        JsonNode failed = awaitDeliveryStatus(t, id, "FAILED");
        assertThat(failed.get("attempts").asInt()).isEqualTo(3);
        assertThat(failed.get("lastError").asText()).isEqualTo("http_500");

        mvc.perform(get("/v1/webhooks/" + id).header("Authorization", t.bearer()))
                .andExpect(jsonPath("$.enabled").value(false));
        int before = receiver.requests.size();
        openAccount(t, "ZAR", false);   // disabled: nothing queued
        Thread.sleep(500);
        assertThat(receiver.requests).hasSize(before);
        mvc.perform(post("/v1/webhooks/" + id + "/test").header("Authorization", t.bearer()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("WEBHOOK_ENDPOINT_DISABLED"));

        receiver.respondAlways(200);
        mvc.perform(post("/v1/webhooks/" + id + "/enable").header("Authorization", t.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(true));
        mvc.perform(post("/v1/webhooks/deliveries/" + failed.get("id").asText() + "/retry")
                        .header("Authorization", t.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING"));
        assertThat(awaitDeliveryStatus(t, id, "SUCCEEDED").get("attempts").asInt()).isEqualTo(1);

        assertThat(auditEvents.findByTenantIdOrderByChainIndexAsc(t.id())).extracting(AuditEvent::getEventType)
                .contains("WEBHOOK_ENDPOINT_CREATED", "WEBHOOK_ENDPOINT_DISABLED", "WEBHOOK_ENDPOINT_ENABLED");
        assertThat(integrity(t).get("auditChainValid").asBoolean()).isTrue();
    }

    @Test
    void rotatedSecretSignsLaterDeliveriesAndTheOldOneStopsWorking() throws Exception {
        Tenant t = newTenant();
        JsonNode created = register(t, receiver.url(), List.of("account.created"));
        String id = created.get("endpoint").get("id").asText();
        String oldSecret = created.get("secret").asText();
        String body = mvc.perform(post("/v1/webhooks/" + id + "/rotate-secret").header("Authorization", t.bearer()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String newSecret = json.readTree(body).get("secret").asText();
        assertThat(newSecret).isNotEqualTo(oldSecret);

        mvc.perform(post("/v1/webhooks/" + id + "/test").header("Authorization", t.bearer()))
                .andExpect(status().isAccepted());
        FakeReceiver.Received got = receiver.await(r -> !r.isEmpty(), WAIT).getFirst();
        long ts = Long.parseLong(got.header("webhook-timestamp"));
        String sig = got.header("webhook-signature");
        assertThat(json.readTree(got.body()).get("type").asText()).isEqualTo("webhook.test");
        assertThat(WebhookSigner.verify(newSecret, got.header("webhook-id"), ts, got.body(), sig)).isTrue();
        assertThat(WebhookSigner.verify(oldSecret, got.header("webhook-id"), ts, got.body(), sig)).isFalse();
    }

    @Test
    void tenantsCannotSeeOrChangeEachOthersEndpoints() throws Exception {
        Tenant owner = newTenant();
        Tenant other = newTenant();
        String id = register(owner, receiver.url(), List.of("transfer.posted")).get("endpoint").get("id").asText();

        mvc.perform(get("/v1/webhooks/" + id).header("Authorization", other.bearer())).andExpect(status().isNotFound());
        mvc.perform(get("/v1/webhooks/" + id + "/deliveries").header("Authorization", other.bearer()))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/v1/webhooks/" + id).header("Authorization", other.bearer())).andExpect(status().isNotFound());
        mvc.perform(post("/v1/webhooks/" + id + "/rotate-secret").header("Authorization", other.bearer()))
                .andExpect(status().isNotFound());
        mvc.perform(post("/v1/webhooks/" + id + "/enable").header("Authorization", other.bearer()))
                .andExpect(status().isNotFound());
        mvc.perform(post("/v1/webhooks/" + id + "/test").header("Authorization", other.bearer()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/v1/webhooks").header("Authorization", other.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(post("/v1/webhooks/" + id + "/test").header("Authorization", owner.bearer()))
                .andExpect(status().isAccepted());
        receiver.await(r -> !r.isEmpty(), WAIT);
        String deliveryId = awaitDeliveryStatus(owner, id, "SUCCEEDED").get("id").asText();
        mvc.perform(post("/v1/webhooks/deliveries/" + deliveryId + "/retry").header("Authorization", other.bearer()))
                .andExpect(status().isNotFound());
        receiver.requests.clear();

        // The other tenant's activity never reaches the owner's endpoint.
        openAccount(other, "ZAR", true);
        Thread.sleep(400);
        assertThat(receiver.requests).isEmpty();
    }

    @Test
    void deletingCancelsPendingDeliveries() throws Exception {
        receiver.respondAlways(500);
        Tenant t = newTenant();
        String id = register(t, receiver.url(), List.of("account.created")).get("endpoint").get("id").asText();
        openAccount(t, "ZAR", false);
        receiver.await(r -> !r.isEmpty(), WAIT);
        mvc.perform(delete("/v1/webhooks/" + id).header("Authorization", t.bearer())).andExpect(status().isNoContent());
        Thread.sleep(300); // an attempt already on the wire when the delete committed may still land
        int seen = receiver.requests.size();
        Thread.sleep(800);
        assertThat(receiver.requests).as("no retries after deletion").hasSize(seen);
        mvc.perform(get("/v1/webhooks/" + id).header("Authorization", t.bearer())).andExpect(status().isNotFound());
        var rows = deliveryRows.findForEndpoint(UUID.fromString(id), t.id(), PageRequest.of(0, 10)).getContent();
        assertThat(rows).isNotEmpty().allSatisfy(d ->
                assertThat(d.getStatus()).isIn(WebhookDelivery.Status.CANCELLED, WebhookDelivery.Status.FAILED));
    }

    @Test
    void invalidRegistrationsAreRefused() throws Exception {
        Tenant t = newTenant();
        mvc.perform(post("/v1/webhooks").header("Authorization", t.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("url", receiver.url(), "eventTypes", List.of("money.stolen")))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("UNKNOWN_EVENT_TYPE"));
        mvc.perform(post("/v1/webhooks").header("Authorization", t.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("url", "file:///etc/passwd", "eventTypes", List.of("transfer.posted")))))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value(WebhookUrlPolicy.CODE));
        mvc.perform(post("/v1/webhooks").header("Authorization", t.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("url", receiver.url(), "eventTypes", List.of()))))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/v1/webhooks").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("url", receiver.url(), "eventTypes", List.of("transfer.posted")))))
                .andExpect(status().isUnauthorized());
    }

    private String token(Tenant t, String scope) throws Exception {
        String body = mvc.perform(post("/oauth/token").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "client_credentials").param("scope", scope)
                        .param("client_id", t.keyId().toString()).param("client_secret", t.apiKey()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return "Bearer " + json.readTree(body).get("access_token").asText();
    }

    @Test
    void managingWebhooksNeedsReadAsWellAsWrite() throws Exception {
        // A webhook streams ledger data out, so a write-only client must not be able to set one up.
        Tenant t = newTenant();
        String writeOnly = token(t, "ledger:write");
        String readOnly = token(t, "ledger:read");
        String both = token(t, "ledger:read ledger:write");
        String body = json.writeValueAsString(Map.of("url", receiver.url(), "eventTypes", List.of("transfer.posted")));
        mvc.perform(post("/v1/webhooks").header("Authorization", writeOnly).contentType(MediaType.APPLICATION_JSON)
                .content(body)).andExpect(status().isForbidden());
        mvc.perform(post("/v1/webhooks").header("Authorization", readOnly).contentType(MediaType.APPLICATION_JSON)
                .content(body)).andExpect(status().isForbidden());
        mvc.perform(get("/v1/webhooks").header("Authorization", writeOnly)).andExpect(status().isForbidden());
        mvc.perform(get("/v1/webhooks").header("Authorization", readOnly)).andExpect(status().isOk());
        mvc.perform(post("/v1/webhooks").header("Authorization", both).contentType(MediaType.APPLICATION_JSON)
                .content(body)).andExpect(status().isCreated());
    }

    @Test
    void endpointCountIsLimitedPerTenant() throws Exception {
        Tenant t = newTenant();
        for (int i = 0; i < 10; i++) {
            register(t, receiver.url(), List.of("transfer.posted"));
        }
        mvc.perform(post("/v1/webhooks").header("Authorization", t.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("url", receiver.url(), "eventTypes", List.of("transfer.posted")))))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("WEBHOOK_LIMIT_REACHED"));
    }

    @Test
    void anInstanceWhoseLeaseWasTakenOverCannotRecord() throws Exception {
        Tenant t = newTenant();
        UUID endpointId = UUID.fromString(register(t, receiver.url(), List.of("transfer.posted"))
                .get("endpoint").get("id").asText());
        // Not due for an hour, so no dispatcher touches it: this test plays two instances by hand.
        Instant later = Instant.now().plus(Duration.ofHours(1)).truncatedTo(ChronoUnit.MICROS);
        UUID deliveryId = tx.execute(s -> {
            UUID eventId = UUID.randomUUID();
            events.save(new WebhookEvent(eventId, t.id(), "transfer.posted", "{}", Instant.now()));
            return deliveryRows.save(new WebhookDelivery(t.id(), endpointId, eventId, later)).getId();
        });
        Instant seen = deliveryRows.findById(deliveryId).orElseThrow().getNextAttemptAt();
        Instant leaseA = seen.plusSeconds(60);
        Instant leaseB = leaseA.plusSeconds(60);
        assertThat(recorder.claim(deliveryId, seen, leaseA)).isTrue();          // instance A
        assertThat(recorder.claim(deliveryId, leaseA, leaseB)).isTrue();        // A's lease ran out; B takes over
        assertThat(recorder.claim(deliveryId, seen, leaseB)).isFalse();         // nobody claims with a stale view

        recorder.record(deliveryId, leaseA, 500, "http_500");                   // A finally reports: dropped
        WebhookDelivery afterA = deliveryRows.findById(deliveryId).orElseThrow();
        assertThat(afterA.getAttempts()).isZero();
        assertThat(afterA.getNextAttemptAt()).isEqualTo(leaseB);
        assertThat(recorder.prepare(deliveryId, leaseA)).isEmpty();

        recorder.record(deliveryId, leaseB, 200, null);                         // B's result counts
        WebhookDelivery afterB = deliveryRows.findById(deliveryId).orElseThrow();
        assertThat(afterB.getStatus()).isEqualTo(WebhookDelivery.Status.SUCCEEDED);
        assertThat(afterB.getAttempts()).isEqualTo(1);
    }

    @Test
    void aSlowReceiverDoesNotHoldUpOtherTenants() throws Exception {
        try (FakeReceiver slow = new FakeReceiver()) {
            slow.delay(Duration.ofSeconds(3));
            Tenant noisy = newTenant();
            register(noisy, slow.url(), List.of("account.created"));
            for (int i = 0; i < 30; i++) {
                openAccount(noisy, "ZAR", false);   // a backlog older than the other tenant's event
            }
            Tenant quiet = newTenant();
            register(quiet, receiver.url(), List.of("account.created"));
            long start = System.nanoTime();
            openAccount(quiet, "ZAR", false);
            receiver.await(r -> !r.isEmpty(), Duration.ofSeconds(2));
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
            assertThat(slow.requests.size()).as("the slow endpoint only ever has a few sends in flight")
                    .isLessThan(30);
        }
    }
}

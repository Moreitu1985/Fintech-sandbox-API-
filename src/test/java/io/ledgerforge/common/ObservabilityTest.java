package io.ledgerforge.common;

import io.ledgerforge.IntegrationTestBase;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.test.context.TestPropertySource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureObservability
@TestPropertySource(properties = "ledgerforge.metrics-token=" + ObservabilityTest.METRICS_TOKEN)
class ObservabilityTest extends IntegrationTestBase {

    static final String METRICS_TOKEN = "test-metrics-token-0123456789abcdef-xyz";

    @Test
    void metricsNeedTheReadOnlyMetricsToken() throws Exception {
        Tenant t = newTenant();
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/prometheus").header("Authorization", t.bearer()))
                .andExpect(status().isForbidden());                       // tenant keys can't read metrics
        mvc.perform(get("/actuator/prometheus").header("X-Admin-Token", ADMIN_TOKEN))
                .andExpect(status().isForbidden());                       // nor can the admin token
        mvc.perform(get("/actuator/prometheus").header("Authorization", "Bearer wrong-" + METRICS_TOKEN))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/prometheus").header("Authorization", "Bearer " + METRICS_TOKEN))
                .andExpect(status().isOk());
    }

    @Test
    void metricsTokenCannotCallTheApi() throws Exception {
        mvc.perform(get("/v1/accounts").header("Authorization", "Bearer " + METRICS_TOKEN))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void businessAndHttpMetricsAreExported() throws Exception {
        Tenant t = newTenant();
        UUID treasury = openAccount(t, "ZAR", true);
        UUID alice = openAccount(t, "ZAR", false);
        transfer(t, newKey(), treasury, alice, 1_000, "ZAR").andExpect(status().isCreated());
        transfer(t, newKey(), alice, treasury, 999_999, "ZAR").andExpect(status().isUnprocessableEntity());

        String body = mvc.perform(get("/actuator/prometheus").header("Authorization", "Bearer " + METRICS_TOKEN))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(body)
                .containsPattern("ledgerforge_transfers_total\\{[^}]*outcome=\"posted\"")
                .containsPattern("ledgerforge_transfers_total\\{[^}]*reason=\"INSUFFICIENT_FUNDS\"")
                .contains("http_server_requests_seconds")
                .contains("hikaricp_connections")
                .contains("jvm_memory_used_bytes");
    }

    @Autowired
    private Tracer tracer;

    @Test
    void tracingBridgeWorksWithTheOpenTelemetryVersionInUse() {
        // OpenTelemetry is pinned above Boot's managed version for security fixes; prove the bridge still works.
        Span span = tracer.nextSpan().name("compatibility-check").start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            assertThat(tracer.currentSpan()).isNotNull();
            assertThat(span.context().traceId()).hasSize(32);
        } finally {
            span.end();
        }
    }
}

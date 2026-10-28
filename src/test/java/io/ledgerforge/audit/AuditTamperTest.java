package io.ledgerforge.audit;

import io.ledgerforge.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/** Simulates an attacker with direct database access editing history. The chain must expose it. */
class AuditTamperTest extends IntegrationTestBase {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void editingAnAuditRecordIsDetected() throws Exception {
        Tenant t = newTenant(); // chain: 0 TENANT_CREATED, 1 API_KEY_ISSUED
        openAccount(t, "ZAR", false); // 2 ACCOUNT_OPENED
        openAccount(t, "ZAR", false); // 3 ACCOUNT_OPENED

        assertThat(integrity(t).get("auditChainValid").asBoolean()).isTrue();

        // H2 only: PostgreSQL blocks this UPDATE outright with an append-only trigger.
        int updated = jdbc.update("update audit_events set payload = ? where tenant_id = ? and chain_index = 2",
                "accountId=forged", t.id());
        assertThat(updated).isEqualTo(1);

        var report = integrity(t);
        assertThat(report.get("healthy").asBoolean()).isFalse();
        assertThat(report.get("auditChainValid").asBoolean()).isFalse();
        assertThat(report.get("auditFirstBrokenIndex").asLong()).isEqualTo(2);
    }

    @Test
    void deletingTheNewestAuditRecordIsDetected() throws Exception {
        Tenant t = newTenant();           // 0, 1
        openAccount(t, "ZAR", false);     // 2
        assertThat(integrity(t).get("auditChainValid").asBoolean()).isTrue();

        // Removing the tail leaves a chain whose links still verify; the stored head exposes it.
        int deleted = jdbc.update("delete from audit_events where tenant_id = ? and chain_index = 2", t.id());
        assertThat(deleted).isEqualTo(1);

        var report = integrity(t);
        assertThat(report.get("auditChainValid").asBoolean()).isFalse();
        assertThat(report.get("auditFirstBrokenIndex").asLong()).isEqualTo(2);
    }
}

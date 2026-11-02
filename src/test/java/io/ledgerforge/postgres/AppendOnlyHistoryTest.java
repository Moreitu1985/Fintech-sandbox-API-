package io.ledgerforge.postgres;

import io.ledgerforge.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves the database itself refuses to rewrite money history, even for someone with the
 * application's own database credentials (V2__append_only_guards.sql). On H2 this protection
 * does not exist, which is why AuditTamperTest can only show that tampering is detected.
 */
@PostgresTest
class AppendOnlyHistoryTest extends IntegrationTestBase {

    @Autowired
    private JdbcTemplate jdbc;

    private Tenant tenant;

    @BeforeEach
    void setUp() throws Exception {
        tenant = newTenant();
        UUID treasury = openAccount(tenant, "ZAR", true);
        UUID alice = openAccount(tenant, "ZAR", false);
        transfer(tenant, newKey(), treasury, alice, 5_000, "ZAR").andExpect(status().isCreated());
    }

    @Test
    void postingsCannotBeChangedOrDeleted() {
        assertRejected("update postings set amount = amount * 2 where tenant_id = ?");
        assertRejected("delete from postings where tenant_id = ?");
    }

    @Test
    void transfersCannotBeChangedOrDeleted() {
        assertRejected("update ledger_transactions set amount = 1 where tenant_id = ?");
        assertRejected("delete from ledger_transactions where tenant_id = ?");
    }

    @Test
    void auditEventsCannotBeChangedOrDeleted() {
        assertRejected("update audit_events set payload = 'forged' where tenant_id = ?");
        assertRejected("delete from audit_events where tenant_id = ?");
    }

    @Test
    void historyIsIntactAfterTheAttempts() throws Exception {
        postingsCannotBeChangedOrDeleted();
        auditEventsCannotBeChangedOrDeleted();
        var report = integrity(tenant);
        assertThat(report.get("healthy").asBoolean()).isTrue();
        assertThat(report.get("auditChainValid").asBoolean()).isTrue();
        assertThat(report.get("postingsSum").asLong()).isZero();
    }

    private void assertRejected(String sql) {
        assertThatThrownBy(() -> jdbc.update(sql, tenant.id()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
    }
}

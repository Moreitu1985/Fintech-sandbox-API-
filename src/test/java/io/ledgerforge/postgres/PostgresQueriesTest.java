package io.ledgerforge.postgres;

import io.ledgerforge.IntegrationTestBase;
import io.ledgerforge.audit.AuditService;
import io.ledgerforge.ledger.LedgerTransactionRepository;
import io.ledgerforge.ledger.PostingRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The hand-written queries behind the fraud check and the AI assistant, and the independent
 * audit commit used for FRAUD_BLOCK, checked on the real database rather than H2.
 */
@PostgresTest
class PostgresQueriesTest extends IntegrationTestBase {

    @Autowired
    private PostingRepository postings;
    @Autowired
    private LedgerTransactionRepository transactions;
    @Autowired
    private AuditService audit;
    @Autowired
    private PlatformTransactionManager txManager;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void behaviourAndSummaryQueriesReturnCorrectFigures() throws Exception {
        Tenant t = newTenant();
        UUID treasury = openAccount(t, "ZAR", true);
        UUID alice = openAccount(t, "ZAR", false);
        UUID bob = openAccount(t, "ZAR", false);
        transfer(t, newKey(), treasury, alice, 10_000, "ZAR").andExpect(status().isCreated());
        transfer(t, newKey(), alice, bob, 1_500, "ZAR").andExpect(status().isCreated());
        transfer(t, newKey(), alice, bob, 2_500, "ZAR").andExpect(status().isCreated());

        Instant hourAgo = Instant.now().minus(Duration.ofHours(1));
        assertThat(postings.countOutgoingSince(alice, hourAgo)).isEqualTo(2);
        assertThat(postings.sumOutgoingSince(alice, hourAgo)).isEqualTo(4_000);
        assertThat(postings.countOutgoing(alice)).isEqualTo(2);
        assertThat(postings.sumOutgoingSince(bob, hourAgo)).isZero();
        assertThat(transactions.existsByTenantIdAndFromAccountIdAndToAccountId(t.id(), alice, bob)).isTrue();
        assertThat(transactions.existsByTenantIdAndFromAccountIdAndToAccountId(t.id(), bob, alice)).isFalse();

        var rows = postings.summarizeByAccount(t.id(), hourAgo);
        assertThat(rows).hasSize(3);
        for (Object[] row : rows) {
            UUID account = (UUID) row[0];
            long entries = ((Number) row[1]).longValue();
            long sent = ((Number) row[2]).longValue();
            long received = ((Number) row[3]).longValue();
            if (account.equals(alice)) {
                assertThat(new long[]{entries, sent, received}).containsExactly(3, 4_000, 10_000);
            } else if (account.equals(bob)) {
                assertThat(new long[]{entries, sent, received}).containsExactly(2, 0, 4_000);
            } else {
                assertThat(new long[]{entries, sent, received}).containsExactly(1, 10_000, 0);
            }
        }
    }

    @Test
    void independentAuditEventSurvivesTheCallersRollback() throws Exception {
        Tenant t = newTenant();
        TransactionTemplate outer = new TransactionTemplate(txManager);
        outer.executeWithoutResult(status -> {
            audit.appendIndependently(t.id(), "FRAUD_BLOCK", "from=x;to=y;amount=1;reasons=test");
            status.setRollbackOnly(); // what a blocked transfer does
        });
        Integer kept = jdbc.queryForObject(
                "select count(*) from audit_events where tenant_id = ? and event_type = 'FRAUD_BLOCK'",
                Integer.class, t.id());
        assertThat(kept).isEqualTo(1);
        assertThat(integrity(t).get("auditChainValid").asBoolean()).isTrue();
    }
}

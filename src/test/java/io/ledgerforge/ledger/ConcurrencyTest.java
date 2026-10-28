package io.ledgerforge.ledger;

import io.ledgerforge.IntegrationTestBase;
import io.ledgerforge.common.error.ApiException;
import io.ledgerforge.ledger.dto.LedgerDtos.TransferRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The test that matters most: hammer a small set of accounts with parallel transfers in random
 * directions and prove that no money is created or destroyed and no balance goes negative.
 */
class ConcurrencyTest extends IntegrationTestBase {

    private static final int ACCOUNTS = 4;
    private static final int TRANSFERS = 200;
    private static final long STARTING_BALANCE = 10_000;

    @Autowired
    private TransferService transferService;

    @Test
    void parallelTransfersConserveMoney() throws Exception {
        Tenant t = newTenant();
        UUID treasury = openAccount(t, "ZAR", true);
        List<UUID> accounts = new ArrayList<>();
        for (int i = 0; i < ACCOUNTS; i++) {
            UUID id = openAccount(t, "ZAR", false);
            transfer(t, newKey(), treasury, id, STARTING_BALANCE, "ZAR").andExpect(status().isCreated());
            accounts.add(id);
        }

        AtomicInteger posted = new AtomicInteger();
        AtomicInteger declined = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<?>> futures = new ArrayList<>();
        Random random = new Random(42);
        for (int i = 0; i < TRANSFERS; i++) {
            UUID from = accounts.get(random.nextInt(ACCOUNTS));
            UUID to = accounts.get((accounts.indexOf(from) + 1 + random.nextInt(ACCOUNTS - 1)) % ACCOUNTS);
            long amount = 1 + random.nextInt(3_000);
            futures.add(pool.submit(() -> {
                try {
                    transferService.transfer(t.id(), newKey(), new TransferRequest(from, to, amount, "ZAR", null, null));
                    posted.incrementAndGet();
                } catch (ApiException e) {
                    assertThat(e.code()).isEqualTo("INSUFFICIENT_FUNDS");
                    declined.incrementAndGet();
                }
            }));
        }
        for (Future<?> f : futures) {
            f.get(60, TimeUnit.SECONDS); // rethrows any unexpected failure
        }
        pool.shutdown();

        assertThat(posted.get() + declined.get()).isEqualTo(TRANSFERS);
        assertThat(posted.get()).isPositive();

        long total = 0;
        for (UUID id : accounts) {
            long balance = balance(t, id);
            assertThat(balance).as("no customer balance may go negative").isGreaterThanOrEqualTo(0);
            total += balance;
        }
        assertThat(total).as("money is neither created nor destroyed").isEqualTo(ACCOUNTS * STARTING_BALANCE);

        var report = integrity(t);
        assertThat(report.get("healthy").asBoolean()).isTrue();
        assertThat(report.get("auditChainValid").asBoolean()).isTrue();
    }
}

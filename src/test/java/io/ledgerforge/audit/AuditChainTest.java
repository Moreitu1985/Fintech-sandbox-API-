package io.ledgerforge.audit;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AuditChainTest {

    private final UUID tenant = UUID.randomUUID();

    private List<AuditChain.Link> chainOf(int n) {
        List<AuditChain.Link> links = new ArrayList<>();
        String prev = AuditChain.GENESIS;
        for (int i = 0; i < n; i++) {
            String payload = "event-" + i;
            String hash = AuditChain.computeHash(prev, tenant, i, "TEST", payload, 1_700_000_000_000L + i);
            links.add(new AuditChain.Link(i, "TEST", payload, 1_700_000_000_000L + i, prev, hash));
            prev = hash;
        }
        return links;
    }

    @Test
    void validChainVerifies() {
        var result = AuditChain.verify(tenant, chainOf(5));
        assertThat(result.valid()).isTrue();
        assertThat(result.eventsChecked()).isEqualTo(5);
        assertThat(result.firstBrokenIndex()).isNull();
    }

    @Test
    void emptyChainIsValid() {
        assertThat(AuditChain.verify(tenant, List.of()).valid()).isTrue();
    }

    @Test
    void editedPayloadIsDetectedAtThatIndex() {
        var links = chainOf(5);
        var original = links.get(2);
        links.set(2, new AuditChain.Link(2, "TEST", "tampered", original.createdAtMillis(), original.prevHash(),
                original.hash()));
        var result = AuditChain.verify(tenant, links);
        assertThat(result.valid()).isFalse();
        assertThat(result.firstBrokenIndex()).isEqualTo(2L);
    }

    @Test
    void deletedEventIsDetected() {
        var links = chainOf(5);
        links.remove(3);
        var result = AuditChain.verify(tenant, links);
        assertThat(result.valid()).isFalse();
        assertThat(result.firstBrokenIndex()).isEqualTo(3L);
    }

    @Test
    void deletingNewestEventsIsDetectedByTheHead() {
        var links = chainOf(5);
        var head = links.get(4);
        var truncated = new ArrayList<>(links.subList(0, 3));
        assertThat(AuditChain.verify(tenant, truncated).valid()).as("links alone look fine").isTrue();
        var result = AuditChain.verify(tenant, truncated, head.index(), head.hash());
        assertThat(result.valid()).isFalse();
        assertThat(result.firstBrokenIndex()).isEqualTo(3L);
        assertThat(AuditChain.verify(tenant, links, head.index(), head.hash()).valid()).isTrue();
    }

    @Test
    void chainFromAnotherTenantDoesNotVerify() {
        assertThat(AuditChain.verify(UUID.randomUUID(), chainOf(3)).valid()).isFalse();
    }
}

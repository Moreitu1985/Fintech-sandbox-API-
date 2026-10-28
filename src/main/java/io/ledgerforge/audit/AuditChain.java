package io.ledgerforge.audit;

import io.ledgerforge.common.Hashing;

import java.util.List;
import java.util.UUID;

/**
 * Pure hash-chain logic. Each event's hash covers the previous event's hash, so editing,
 * deleting or reordering any past event breaks every link after it.
 */
public final class AuditChain {

    public static final String GENESIS = "0".repeat(64);

    private AuditChain() {
    }

    public record Link(long index, String eventType, String payload, long createdAtMillis, String prevHash, String hash) {
    }

    /** firstBrokenIndex is null when the chain is valid. */
    public record Verification(boolean valid, long eventsChecked, Long firstBrokenIndex) {
    }

    public static String computeHash(String prevHash, UUID tenantId, long index, String eventType,
                                     String payload, long createdAtMillis) {
        // Payload goes last so its content cannot be confused with the fixed-format fields before it.
        String material = String.join("\n",
                prevHash, tenantId.toString(), Long.toString(index), eventType, Long.toString(createdAtMillis), payload);
        return Hashing.sha256Hex(material);
    }

    /**
     * Verifies the links and that the chain ends at the recorded head. The head check catches
     * deletion of the newest events, which leaves an otherwise valid-looking shorter chain.
     */
    public static Verification verify(UUID tenantId, List<Link> links, Long headIndex, String headHash) {
        Verification base = verify(tenantId, links);
        if (!base.valid()) {
            return base;
        }
        Link last = links.isEmpty() ? null : links.get(links.size() - 1);
        boolean headMatches = last == null
                ? headIndex == null
                : headIndex != null && last.index() == headIndex && last.hash().equals(headHash);
        return headMatches ? base : new Verification(false, base.eventsChecked(), base.eventsChecked());
    }

    public static Verification verify(UUID tenantId, List<Link> links) {
        String expectedPrev = GENESIS;
        long expectedIndex = 0;
        for (Link link : links) {
            String recomputed = computeHash(link.prevHash(), tenantId, link.index(), link.eventType(),
                    link.payload(), link.createdAtMillis());
            boolean ok = link.index() == expectedIndex
                    && link.prevHash().equals(expectedPrev)
                    && link.hash().equals(recomputed);
            if (!ok) {
                return new Verification(false, expectedIndex, expectedIndex);
            }
            expectedPrev = link.hash();
            expectedIndex++;
        }
        return new Verification(true, expectedIndex, null);
    }
}

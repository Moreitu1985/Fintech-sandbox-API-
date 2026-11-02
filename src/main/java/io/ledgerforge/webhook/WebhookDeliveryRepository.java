package io.ledgerforge.webhook;

import io.ledgerforge.webhook.WebhookDelivery.Status;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WebhookDeliveryRepository extends JpaRepository<WebhookDelivery, UUID> {

    interface Due {
        UUID getId();

        Instant getNextAttemptAt();
    }

    @Query("select d.id as id, d.nextAttemptAt as nextAttemptAt from WebhookDelivery d "
            + "where d.status = :status and d.nextAttemptAt <= :now order by d.nextAttemptAt")
    List<Due> findDue(Status status, Instant now, Pageable limit);

    default List<Due> findDue(Instant now, Pageable limit) {
        return findDue(Status.PENDING, now, limit);
    }

    /**
     * Claims a due delivery by moving its next-attempt time to the end of a lease. Compare-and-set on the
     * time read a moment ago, so when several instances race for the same row exactly one wins (portable:
     * no SELECT ... SKIP LOCKED needed). If the instance dies mid-send, the lease runs out and the delivery
     * is picked up again.
     */
    @Modifying
    @Query("update WebhookDelivery d set d.nextAttemptAt = :leaseUntil "
            + "where d.id = :id and d.status = :status and d.nextAttemptAt = :seen")
    int claim(UUID id, Status status, Instant seen, Instant leaseUntil);

    default int claim(UUID id, Instant seen, Instant leaseUntil) {
        return claim(id, Status.PENDING, seen, leaseUntil);
    }

    @Query("select d from WebhookDelivery d where d.endpointId = :endpointId and d.tenantId = :tenantId")
    Page<WebhookDelivery> findForEndpoint(UUID endpointId, UUID tenantId, Pageable page);

    Optional<WebhookDelivery> findByIdAndTenantId(UUID id, UUID tenantId);

    @Modifying
    @Query("update WebhookDelivery d set d.status = :cancelled, d.lastError = :reason "
            + "where d.endpointId = :endpointId and d.status = :pending")
    int cancelPending(UUID endpointId, String reason, Status cancelled, Status pending);

    default int cancelPending(UUID endpointId, String reason) {
        return cancelPending(endpointId, reason, Status.CANCELLED, Status.PENDING);
    }
}

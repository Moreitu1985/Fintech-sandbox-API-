package io.ledgerforge.webhook;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WebhookEndpointRepository extends JpaRepository<WebhookEndpoint, UUID> {

    @Query("select e from WebhookEndpoint e where e.tenantId = :tenantId and e.deletedAt is null order by e.createdAt, e.id")
    List<WebhookEndpoint> findLive(UUID tenantId);

    @Query("select e from WebhookEndpoint e where e.tenantId = :tenantId and e.enabled = true and e.deletedAt is null")
    List<WebhookEndpoint> findActive(UUID tenantId);

    @Query("select e from WebhookEndpoint e where e.id = :id and e.tenantId = :tenantId and e.deletedAt is null")
    Optional<WebhookEndpoint> findLive(UUID id, UUID tenantId);

    /**
     * Resets the failure streak after a success by updating only that column, so it can never write a
     * stale copy of the rest of the row (enabled, deleted, secret) over a concurrent change.
     */
    @Modifying
    @Query("update WebhookEndpoint e set e.failedDeliveriesInRow = 0 where e.id = :id and e.failedDeliveriesInRow <> 0")
    int resetFailureStreak(UUID id);
}

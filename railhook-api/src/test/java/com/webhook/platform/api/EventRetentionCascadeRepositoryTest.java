package com.webhook.platform.api;

import com.webhook.platform.api.domain.repository.EventRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class EventRetentionCascadeRepositoryTest extends AbstractIntegrationTest {

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private EventRepository eventRepository;

    private UUID organizationId;
    private UUID projectId;
    private UUID endpointId;

    @BeforeEach
    void seedTenantChain() {
        organizationId = UUID.randomUUID();
        projectId = UUID.randomUUID();
        endpointId = UUID.randomUUID();
        transactionTemplate.executeWithoutResult(tx -> {
            // plan_id is NOT NULL; any seeded plan will do.
            entityManager.createNativeQuery("""
                    INSERT INTO organizations (id, name, plan_id)
                    VALUES (:id, 'retention-test', (SELECT id FROM plans ORDER BY id LIMIT 1))
                    """)
                    .setParameter("id", organizationId).executeUpdate();
            entityManager.createNativeQuery(
                            "INSERT INTO projects (id, organization_id, name) VALUES (:id, :org, 'p')")
                    .setParameter("id", projectId).setParameter("org", organizationId).executeUpdate();
            entityManager.createNativeQuery("""
                    INSERT INTO endpoints (id, organization_id, project_id, url,
                                           secret_encrypted, secret_iv)
                    VALUES (:id, :org, :project, 'https://example.com/hook', 'x', 'y')
                    """)
                    .setParameter("id", endpointId)
                    .setParameter("org", organizationId)
                    .setParameter("project", projectId).executeUpdate();
        });
    }

    @Test
    void deletingAnExpiredEventTakesItsDeliveriesAndAttempts() {
        UUID eventId = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();

        transactionTemplate.executeWithoutResult(tx -> {
            seedEvent(eventId, Instant.now().minusSeconds(200L * 86400L));
            seedDelivery(deliveryId, eventId, "FAILED");
            seedAttempt(deliveryId);
        });

        // The repository method itself, not a copy of its SQL.
        transactionTemplate.execute(tx ->
                eventRepository.deleteOldEvents(Instant.now().minusSeconds(90L * 86400L), 1000));

        // Asserted per row: the shared container may hold other classes' expired events.
        assertEquals(0L, countWhere("events", "id", eventId), "the expired event itself");
        assertEquals(0L, countWhere("deliveries", "id", deliveryId),
                "deliveries.event_id must cascade, or a purge leaves rows nothing can reach");
        assertEquals(0L, countWhere("delivery_attempts", "delivery_id", deliveryId),
                "delivery_attempts.delivery_id must cascade through the delivery — this is the "
                        + "constraint V052 dropped and V061 restored");
    }

    @Test
    void anEventWithAnInFlightDeliveryIsLeftAlone() {
        UUID eventId = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();

        transactionTemplate.executeWithoutResult(tx -> {
            seedEvent(eventId, Instant.now().minusSeconds(400L * 86400L));
            seedDelivery(deliveryId, eventId, "PROCESSING");
        });

        transactionTemplate.execute(tx ->
                eventRepository.deleteOldEvents(Instant.now().minusSeconds(90L * 86400L), 1000));

        assertEquals(1L, countWhere("events", "id", eventId),
                "age alone must not beat an in-flight delivery");
    }

    private void seedEvent(UUID eventId, Instant createdAt) {
        entityManager.createNativeQuery("""
                INSERT INTO events (id, organization_id, project_id, event_type, payload, created_at)
                VALUES (:id, :org, :project, 'retention.test', '{}'::jsonb, :createdAt)
                """)
                .setParameter("id", eventId)
                .setParameter("org", organizationId)
                .setParameter("project", projectId)
                .setParameter("createdAt", createdAt)
                .executeUpdate();
    }

    private void seedDelivery(UUID deliveryId, UUID eventId, String status) {
        entityManager.createNativeQuery("""
                INSERT INTO deliveries (id, organization_id, event_id, endpoint_id, status,
                                        attempt_count, max_attempts, created_at)
                VALUES (:id, :org, :eventId, :endpointId, :status, 1, 6, NOW())
                """)
                .setParameter("id", deliveryId)
                .setParameter("org", organizationId)
                .setParameter("eventId", eventId)
                .setParameter("endpointId", endpointId)
                .setParameter("status", status)
                .executeUpdate();
    }

    private void seedAttempt(UUID deliveryId) {
        entityManager.createNativeQuery("""
                INSERT INTO delivery_attempts (id, organization_id, delivery_id, attempt_number,
                                               http_status_code, created_at)
                VALUES (:id, :org, :deliveryId, 1, 500, NOW())
                """)
                .setParameter("id", UUID.randomUUID())
                .setParameter("org", organizationId)
                .setParameter("deliveryId", deliveryId)
                .executeUpdate();
    }

    private long countWhere(String table, String column, UUID value) {
        Number n = (Number) transactionTemplate.execute(tx ->
                entityManager.createNativeQuery(
                                "SELECT COUNT(*) FROM " + table + " WHERE " + column + " = :v")
                        .setParameter("v", value)
                        .getSingleResult());
        return n.longValue();
    }
}

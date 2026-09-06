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

/**
 * Event retention deletes one row and must take a whole tree with it.
 *
 * <p>{@code DataRetentionService.cleanupOldEvents} issues a single {@code DELETE FROM events}
 * and relies on two cascades to reach everything beneath: {@code deliveries.event_id} (V001)
 * and {@code delivery_attempts.delivery_id} (V001, dropped by V052, restored by V061). If
 * either is missing the delete silently leaves orphans that nothing can reach again —
 * {@code event_id} and {@code delivery_id} are the only ways in — and the retention that is
 * supposed to bound the database bounds only its smallest table.
 *
 * <p>Asserted against real PostgreSQL rather than by reading the migrations, because the last
 * time this chain broke it broke in a migration that faithfully re-created every column and
 * every index and dropped one constraint without mentioning it.
 *
 * <p>The second half is the guard that keeps this safe to run: an event with a delivery still
 * PENDING or PROCESSING is left alone however old it is. Those rows are owned by the worker —
 * a claim may be live on one — and deleting an event out from under an in-flight attempt is a
 * far worse failure than keeping it another day.
 */
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
            // plan_id is NOT NULL and the plans are seeded by V036; any of them will do,
            // because nothing here depends on which plan the organization is on.
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

        // The repository method itself, not a copy of its SQL: a test that restates the query
        // proves PostgreSQL works, not that this code is right, and drifts the moment the query
        // is edited.
        transactionTemplate.execute(tx ->
                eventRepository.deleteOldEvents(Instant.now().minusSeconds(90L * 86400L), 1000));

        // Asserted per row rather than on the returned count: the container is shared across
        // test classes, so how many *other* expired events one batch happened to sweep up is
        // not this test's business.
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

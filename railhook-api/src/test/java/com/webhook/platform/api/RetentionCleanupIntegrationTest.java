package com.webhook.platform.api;

import com.webhook.platform.api.service.DataRetentionService;
import com.webhook.platform.api.service.billing.RetentionCleanupScheduler;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The two nightly jobs that delete events, run the way the scheduler runs them: outside any
 * transaction a test opened, so a rollback inside them is a rollback the test can see.
 *
 * <p>Two production failures are pinned here. A workflow trigger that had exhausted its retries
 * kept a FAILED row in {@code workflow_trigger_outbox}, whose foreign key to {@code events} had no
 * {@code ON DELETE}; the first expired event carrying one failed the delete, and because the whole
 * job was one transaction every attempt and delivery it had already removed that night came back.
 * Every night, for as long as the row existed — which was forever, since outbox cleanup only
 * collects DONE rows.
 *
 * <p>And plan retention measured only the event's age. A six-day-old event replayed on a
 * seven-day plan has a fresh PENDING delivery with a retry scheduled; the next night deleted it,
 * attempts and all, mid-ladder.
 */
@TestPropertySource(properties = "billing.enabled=true")
class RetentionCleanupIntegrationTest extends AbstractIntegrationTest {

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private RetentionCleanupScheduler retentionCleanupScheduler;

    @Autowired
    private DataRetentionService dataRetentionService;

    private UUID organizationId;
    private UUID projectId;
    private UUID endpointId;

    @BeforeEach
    void seedFreePlanOrganization() {
        organizationId = UUID.randomUUID();
        projectId = UUID.randomUUID();
        endpointId = UUID.randomUUID();
        transactionTemplate.executeWithoutResult(tx -> {
            // The jobs are called directly, but still through their @SchedulerLock, whose
            // lockAtLeastFor would make every call after the first in this class a silent no-op.
            // Expired rather than deleted: the provider remembers the row exists and only ever
            // updates it afterwards, so a deleted row can never be acquired again.
            entityManager.createNativeQuery(
                    "UPDATE shedlock SET lock_until = TIMESTAMP '2000-01-01 00:00:00'").executeUpdate();

            // The free plan keeps seven days, which is what makes an eight-day-old event expired.
            entityManager.createNativeQuery("""
                    INSERT INTO organizations (id, name, plan_id)
                    VALUES (:id, 'plan-retention-test', (SELECT id FROM plans WHERE name = 'free'))
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
    void planRetentionRemovesAnEventWhoseWorkflowTriggerFailed_andKeepsTheRestOfTheNight() {
        UUID plainEvent = UUID.randomUUID();
        UUID plainDelivery = UUID.randomUUID();
        UUID failedTriggerEvent = UUID.randomUUID();
        UUID failedTriggerDelivery = UUID.randomUUID();

        transactionTemplate.executeWithoutResult(tx -> {
            seedEvent(plainEvent, daysAgo(8));
            seedDelivery(plainDelivery, plainEvent, "SUCCESS");
            seedAttempt(plainDelivery);

            seedEvent(failedTriggerEvent, daysAgo(8));
            seedDelivery(failedTriggerDelivery, failedTriggerEvent, "FAILED");
            seedAttempt(failedTriggerDelivery);
            seedWorkflowTrigger(failedTriggerEvent, "FAILED", daysAgo(8));
        });

        assertDoesNotThrow(() -> retentionCleanupScheduler.cleanup());

        assertEquals(0L, countWhere("events", "id", failedTriggerEvent),
                "a FAILED workflow trigger must not pin its event past retention");
        assertEquals(0L, countWhere("workflow_trigger_outbox", "event_id", failedTriggerEvent),
                "the trigger row goes with its event");
        assertEquals(0L, countWhere("delivery_attempts", "delivery_id", failedTriggerDelivery));

        assertEquals(0L, countWhere("events", "id", plainEvent),
                "the other expired event must not come back with a rollback");
        assertEquals(0L, countWhere("deliveries", "id", plainDelivery));
        assertEquals(0L, countWhere("delivery_attempts", "delivery_id", plainDelivery));
    }

    @Test
    void planRetentionLeavesAnEventWhoseDeliveryIsStillInFlight() {
        UUID pendingEvent = UUID.randomUUID();
        UUID pendingDelivery = UUID.randomUUID();
        UUID processingEvent = UUID.randomUUID();
        UUID processingDelivery = UUID.randomUUID();
        UUID settledEvent = UUID.randomUUID();

        transactionTemplate.executeWithoutResult(tx -> {
            // The control: without it a run that deleted nothing at all would pass.
            seedEvent(settledEvent, daysAgo(8));
            seedDelivery(UUID.randomUUID(), settledEvent, "SUCCESS");

            // A replay of an old event: the event is past the plan's seven days, its delivery is
            // new and has a retry scheduled.
            seedEvent(pendingEvent, daysAgo(8));
            seedDelivery(pendingDelivery, pendingEvent, "PENDING");
            seedAttempt(pendingDelivery);

            seedEvent(processingEvent, daysAgo(30));
            seedDelivery(processingDelivery, processingEvent, "PROCESSING");
        });

        assertDoesNotThrow(() -> retentionCleanupScheduler.cleanup());

        assertEquals(0L, countWhere("events", "id", settledEvent), "the run did delete what it may");
        assertEquals(1L, countWhere("events", "id", pendingEvent));
        assertEquals(1L, countWhere("deliveries", "id", pendingDelivery),
                "a PENDING delivery is mid-retry; age alone must not delete it");
        assertEquals(1L, countWhere("delivery_attempts", "delivery_id", pendingDelivery),
                "its attempt history is what the next attempt is numbered from");
        assertEquals(1L, countWhere("events", "id", processingEvent));
        assertEquals(1L, countWhere("deliveries", "id", processingDelivery),
                "a PROCESSING delivery may have a live claim on it");
    }

    @Test
    void globalEventRetentionRemovesAnEventWhoseWorkflowTriggerFailed() {
        UUID eventId = UUID.randomUUID();

        transactionTemplate.executeWithoutResult(tx -> {
            seedEvent(eventId, daysAgo(200));
            seedWorkflowTrigger(eventId, "FAILED", daysAgo(200));
        });

        assertDoesNotThrow(() -> dataRetentionService.cleanupOldEvents());

        assertEquals(0L, countWhere("events", "id", eventId));
        assertEquals(0L, countWhere("workflow_trigger_outbox", "event_id", eventId));
    }

    private static Instant daysAgo(int days) {
        return Instant.now().minusSeconds(days * 86400L);
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

    private void seedWorkflowTrigger(UUID eventId, String status, Instant at) {
        entityManager.createNativeQuery("""
                INSERT INTO workflow_trigger_outbox (project_id, event_id, event_type, status,
                                                     attempts, error, created_at, processed_at)
                VALUES (:project, :eventId, 'retention.test', :status, 3, 'boom', :at, :at)
                """)
                .setParameter("project", projectId)
                .setParameter("eventId", eventId)
                .setParameter("status", status)
                .setParameter("at", at)
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

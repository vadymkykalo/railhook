package com.webhook.platform.api;

import com.webhook.platform.api.domain.repository.DeliveryRepository;
import com.webhook.platform.api.domain.repository.IncomingForwardAttemptRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class DlqRetentionRepositoryTest extends AbstractIntegrationTest {

    private static final long DAY = 86400L;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private DeliveryRepository deliveryRepository;

    @Autowired
    private IncomingForwardAttemptRepository forwardRepository;

    private UUID organizationId;
    private UUID projectId;
    private UUID endpointId;
    private UUID sourceId;
    private UUID destinationId;

    @BeforeEach
    void seedTenantChain() {
        organizationId = UUID.randomUUID();
        projectId = UUID.randomUUID();
        endpointId = UUID.randomUUID();
        sourceId = UUID.randomUUID();
        destinationId = UUID.randomUUID();
        transactionTemplate.executeWithoutResult(tx -> {
            entityManager.createNativeQuery("""
                    INSERT INTO organizations (id, name, plan_id)
                    VALUES (:id, 'dlq-retention-test', (SELECT id FROM plans ORDER BY id LIMIT 1))
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
            entityManager.createNativeQuery("""
                    INSERT INTO incoming_sources (id, organization_id, project_id, name, slug,
                                                  ingress_path_token)
                    VALUES (:id, :org, :project, 's', :slug, :token)
                    """)
                    .setParameter("id", sourceId)
                    .setParameter("org", organizationId)
                    .setParameter("project", projectId)
                    .setParameter("slug", "s-" + sourceId)
                    .setParameter("token", sourceId.toString().replace("-", ""))
                    .executeUpdate();
            entityManager.createNativeQuery("""
                    INSERT INTO incoming_destinations (id, organization_id, incoming_source_id, url)
                    VALUES (:id, :org, :source, 'https://example.com/forward')
                    """)
                    .setParameter("id", destinationId)
                    .setParameter("org", organizationId)
                    .setParameter("source", sourceId).executeUpdate();
        });
    }

    @Test
    void aDeliveryLongInTheDlqGoesWithItsAttemptsWhileRecentAndOtherEndsStay() {
        UUID expired = UUID.randomUUID();
        UUID recent = UUID.randomUUID();
        UUID oldFailed = UUID.randomUUID();

        transactionTemplate.executeWithoutResult(tx -> {
            seedDelivery(expired, "DLQ", Instant.now().minusSeconds(20 * DAY));
            seedAttempt(expired);
            seedDelivery(recent, "DLQ", Instant.now().minusSeconds(2 * DAY));
            seedDelivery(oldFailed, "FAILED", Instant.now().minusSeconds(20 * DAY));
        });

        transactionTemplate.execute(tx ->
                deliveryRepository.deleteExpiredDlq(Instant.now().minusSeconds(14 * DAY), 1000));

        assertEquals(0L, countWhere("deliveries", "id", expired));
        assertEquals(0L, countWhere("delivery_attempts", "delivery_id", expired));
        assertEquals(1L, countWhere("deliveries", "id", recent), "counted from when it entered the DLQ");
        assertEquals(1L, countWhere("deliveries", "id", oldFailed), "only the DLQ expires early");
    }

    @Test
    void aForwardLongInTheDlqGoesWhileARecentOneStays() {
        UUID expired = UUID.randomUUID();
        UUID recent = UUID.randomUUID();

        transactionTemplate.executeWithoutResult(tx -> {
            seedForward(expired, Instant.now().minusSeconds(20 * DAY));
            seedForward(recent, Instant.now().minusSeconds(2 * DAY));
        });

        transactionTemplate.execute(tx ->
                forwardRepository.deleteExpiredDlq(Instant.now().minusSeconds(14 * DAY), 1000));

        assertEquals(0L, countWhere("incoming_forward_attempts", "id", expired));
        assertEquals(1L, countWhere("incoming_forward_attempts", "id", recent));
    }

    private void seedDelivery(UUID deliveryId, String status, Instant failedAt) {
        UUID eventId = UUID.randomUUID();
        entityManager.createNativeQuery("""
                INSERT INTO events (id, organization_id, project_id, event_type, payload, created_at)
                VALUES (:id, :org, :project, 'dlq.test', '{}'::jsonb, NOW())
                """)
                .setParameter("id", eventId)
                .setParameter("org", organizationId)
                .setParameter("project", projectId)
                .executeUpdate();
        entityManager.createNativeQuery("""
                INSERT INTO deliveries (id, organization_id, event_id, endpoint_id, status,
                                        attempt_count, max_attempts, created_at, failed_at)
                VALUES (:id, :org, :eventId, :endpointId, :status, 6, 6, :failedAt, :failedAt)
                """)
                .setParameter("id", deliveryId)
                .setParameter("org", organizationId)
                .setParameter("eventId", eventId)
                .setParameter("endpointId", endpointId)
                .setParameter("status", status)
                .setParameter("failedAt", failedAt)
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

    private void seedForward(UUID forwardId, Instant finishedAt) {
        UUID eventId = UUID.randomUUID();
        entityManager.createNativeQuery("""
                INSERT INTO incoming_events (id, organization_id, incoming_source_id, request_id, method)
                VALUES (:id, :org, :source, :requestId, 'POST')
                """)
                .setParameter("id", eventId)
                .setParameter("org", organizationId)
                .setParameter("source", sourceId)
                .setParameter("requestId", eventId.toString().substring(0, 32))
                .executeUpdate();
        entityManager.createNativeQuery("""
                INSERT INTO incoming_forward_attempts (id, organization_id, incoming_event_id,
                                                       destination_id, status, created_at, finished_at)
                VALUES (:id, :org, :eventId, :destination, 'DLQ', :finishedAt, :finishedAt)
                """)
                .setParameter("id", forwardId)
                .setParameter("org", organizationId)
                .setParameter("eventId", eventId)
                .setParameter("destination", destinationId)
                .setParameter("finishedAt", finishedAt)
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

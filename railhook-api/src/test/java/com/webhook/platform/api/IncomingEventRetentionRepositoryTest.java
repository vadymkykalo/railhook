package com.webhook.platform.api;

import com.webhook.platform.api.domain.repository.IncomingEventRepository;
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
 * Incoming Event retention must not delete a Forward partway through its Ladder.
 *
 * <p>{@code incoming_forward_attempts.incoming_event_id} cascades, so deleting an expired Incoming
 * Event takes its Forward's rows with it. A Replay or a Failed Messages retry starts a fresh
 * Forward for a webhook that may have arrived close to the cutoff, and the nightly purge wiped
 * those rows mid-ladder: the worker's next claim matched nothing and the Forward vanished without
 * reaching the DLQ. Events retention has kept in-flight Deliveries out of its delete all along.
 */
class IncomingEventRetentionRepositoryTest extends AbstractIntegrationTest {

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private IncomingEventRepository incomingEventRepository;

    private UUID organizationId;
    private UUID sourceId;
    private UUID destinationId;

    @BeforeEach
    void seedSourceAndDestination() {
        organizationId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        sourceId = UUID.randomUUID();
        destinationId = UUID.randomUUID();
        transactionTemplate.executeWithoutResult(tx -> {
            entityManager.createNativeQuery("""
                    INSERT INTO organizations (id, name, plan_id)
                    VALUES (:id, 'incoming-retention-test', (SELECT id FROM plans ORDER BY id LIMIT 1))
                    """)
                    .setParameter("id", organizationId).executeUpdate();
            entityManager.createNativeQuery(
                            "INSERT INTO projects (id, organization_id, name) VALUES (:id, :org, 'p')")
                    .setParameter("id", projectId).setParameter("org", organizationId).executeUpdate();
            entityManager.createNativeQuery("""
                    INSERT INTO incoming_sources (id, organization_id, project_id, name, slug, provider_type,
                                                  status, ingress_path_token, verification_mode,
                                                  encryption_key_version, created_at, updated_at)
                    VALUES (:id, :org, :project, 's', :slug, 'GENERIC', 'ACTIVE', :token, 'NONE', 1, NOW(), NOW())
                    """)
                    .setParameter("id", sourceId)
                    .setParameter("org", organizationId)
                    .setParameter("project", projectId)
                    .setParameter("slug", "s-" + sourceId)
                    .setParameter("token", sourceId.toString().replace("-", ""))
                    .executeUpdate();
            entityManager.createNativeQuery("""
                    INSERT INTO incoming_destinations (id, organization_id, incoming_source_id, url,
                                                       created_at, updated_at)
                    VALUES (:id, :org, :source, 'https://example.com/hook', NOW(), NOW())
                    """)
                    .setParameter("id", destinationId)
                    .setParameter("org", organizationId)
                    .setParameter("source", sourceId)
                    .executeUpdate();
        });
    }

    @Test
    void anIncomingEventWhoseForwardIsStillOnItsLadderIsLeftAlone() {
        UUID pendingEvent = UUID.randomUUID();
        UUID processingEvent = UUID.randomUUID();
        transactionTemplate.executeWithoutResult(tx -> {
            seedEvent(pendingEvent, Instant.now().minusSeconds(40L * 86400L));
            seedForwardAttempt(pendingEvent, 1, "FAILED");
            seedForwardAttempt(pendingEvent, 2, "PENDING");
            seedEvent(processingEvent, Instant.now().minusSeconds(40L * 86400L));
            seedForwardAttempt(processingEvent, 1, "PROCESSING");
        });

        transactionTemplate.execute(tx ->
                incomingEventRepository.deleteOldIncomingEvents(Instant.now().minusSeconds(30L * 86400L), 1000));

        assertEquals(1L, countWhere("incoming_events", "id", pendingEvent),
                "a PENDING Forward is mid-ladder; age alone must not delete it");
        assertEquals(2L, countWhere("incoming_forward_attempts", "incoming_event_id", pendingEvent));
        assertEquals(1L, countWhere("incoming_events", "id", processingEvent),
                "a PROCESSING Forward may have a live claim on it");
    }

    @Test
    void anExpiredIncomingEventWhoseForwardsAreDoneIsDeletedWithThem() {
        UUID eventId = UUID.randomUUID();
        transactionTemplate.executeWithoutResult(tx -> {
            seedEvent(eventId, Instant.now().minusSeconds(40L * 86400L));
            seedForwardAttempt(eventId, 1, "SUCCESS");
        });

        transactionTemplate.execute(tx ->
                incomingEventRepository.deleteOldIncomingEvents(Instant.now().minusSeconds(30L * 86400L), 1000));

        assertEquals(0L, countWhere("incoming_events", "id", eventId));
        assertEquals(0L, countWhere("incoming_forward_attempts", "incoming_event_id", eventId));
    }

    private void seedEvent(UUID id, Instant receivedAt) {
        entityManager.createNativeQuery("""
                INSERT INTO incoming_events (id, organization_id, incoming_source_id, request_id, method, received_at)
                VALUES (:id, :org, :source, :requestId, 'POST', :receivedAt)
                """)
                .setParameter("id", id)
                .setParameter("org", organizationId)
                .setParameter("source", sourceId)
                .setParameter("requestId", id.toString().replace("-", ""))
                .setParameter("receivedAt", receivedAt)
                .executeUpdate();
    }

    private void seedForwardAttempt(UUID eventId, int attemptNumber, String status) {
        entityManager.createNativeQuery("""
                INSERT INTO incoming_forward_attempts (id, organization_id, incoming_event_id, destination_id,
                                                       attempt_number, status, created_at)
                VALUES (:id, :org, :eventId, :destinationId, :attemptNumber, :status, NOW())
                """)
                .setParameter("id", UUID.randomUUID())
                .setParameter("org", organizationId)
                .setParameter("eventId", eventId)
                .setParameter("destinationId", destinationId)
                .setParameter("attemptNumber", attemptNumber)
                .setParameter("status", status)
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

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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The database count behind the quota: the number the Redis counter is re-seeded from, the one a
 * quota check falls back to when Redis is down, and the one the usage page shows.
 *
 * <p>Both directions charge the quota, but this count used to read only {@code events}, so every
 * re-seed forgave an organization the incoming webhooks it had received that month and the usage
 * page disagreed with the check that was refusing its requests. Asserted on real PostgreSQL
 * because the thing that can be wrong is the SQL.
 */
class QuotaUsageCountRepositoryTest extends AbstractIntegrationTest {

    private static final Instant FROM = Instant.parse("2026-03-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-04-01T00:00:00Z");

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private EventRepository eventRepository;

    private UUID organizationId;
    private UUID otherOrganizationId;

    @BeforeEach
    void seed() {
        organizationId = UUID.randomUUID();
        otherOrganizationId = UUID.randomUUID();
        transactionTemplate.executeWithoutResult(tx -> {
            UUID project = seedOrganizationWithProject(organizationId);
            UUID source = seedSource(organizationId, project);
            UUID otherProject = seedOrganizationWithProject(otherOrganizationId);
            UUID otherSource = seedSource(otherOrganizationId, otherProject);

            seedEvent(organizationId, project, Instant.parse("2026-03-01T00:00:00Z"));
            seedEvent(organizationId, project, Instant.parse("2026-03-31T23:59:59Z"));
            seedEvent(organizationId, project, Instant.parse("2026-04-01T00:00:00Z"));
            seedIncomingEvent(organizationId, source, Instant.parse("2026-03-10T12:00:00Z"));
            seedIncomingEvent(organizationId, source, Instant.parse("2026-02-28T23:59:59Z"));

            seedEvent(otherOrganizationId, otherProject, Instant.parse("2026-03-10T12:00:00Z"));
            seedIncomingEvent(otherOrganizationId, otherSource, Instant.parse("2026-03-10T12:00:00Z"));
        });
    }

    @Test
    void countsTheMonthsEventsAndIncomingEventsTogether_forThatOrganizationOnly() {
        long count = transactionTemplate.execute(tx ->
                eventRepository.countEventsAndIncomingEventsBetween(organizationId, FROM, TO));

        // Two events and one incoming event inside the half-open month; one of each outside it.
        assertThat(count).isEqualTo(3L);
    }

    private UUID seedOrganizationWithProject(UUID orgId) {
        UUID projectId = UUID.randomUUID();
        entityManager.createNativeQuery("""
                INSERT INTO organizations (id, name, plan_id)
                VALUES (:id, 'quota-count-test', (SELECT id FROM plans WHERE name = 'free'))
                """)
                .setParameter("id", orgId).executeUpdate();
        entityManager.createNativeQuery(
                        "INSERT INTO projects (id, organization_id, name) VALUES (:id, :org, 'p')")
                .setParameter("id", projectId).setParameter("org", orgId).executeUpdate();
        return projectId;
    }

    private UUID seedSource(UUID orgId, UUID projectId) {
        UUID sourceId = UUID.randomUUID();
        entityManager.createNativeQuery("""
                INSERT INTO incoming_sources (id, organization_id, project_id, name, slug, provider_type,
                                              status, ingress_path_token, verification_mode,
                                              encryption_key_version, created_at, updated_at)
                VALUES (:id, :org, :project, 's', :slug, 'GENERIC', 'ACTIVE', :token, 'NONE', 1, NOW(), NOW())
                """)
                .setParameter("id", sourceId)
                .setParameter("org", orgId)
                .setParameter("project", projectId)
                .setParameter("slug", "s-" + sourceId)
                .setParameter("token", sourceId.toString().replace("-", ""))
                .executeUpdate();
        return sourceId;
    }

    private void seedEvent(UUID orgId, UUID projectId, Instant createdAt) {
        entityManager.createNativeQuery("""
                INSERT INTO events (id, organization_id, project_id, event_type, payload, created_at)
                VALUES (:id, :org, :project, 'quota.test', '{}'::jsonb, :createdAt)
                """)
                .setParameter("id", UUID.randomUUID())
                .setParameter("org", orgId)
                .setParameter("project", projectId)
                .setParameter("createdAt", createdAt)
                .executeUpdate();
    }

    private void seedIncomingEvent(UUID orgId, UUID sourceId, Instant receivedAt) {
        UUID id = UUID.randomUUID();
        entityManager.createNativeQuery("""
                INSERT INTO incoming_events (id, organization_id, incoming_source_id, request_id, method, received_at)
                VALUES (:id, :org, :source, :requestId, 'POST', :receivedAt)
                """)
                .setParameter("id", id)
                .setParameter("org", orgId)
                .setParameter("source", sourceId)
                .setParameter("requestId", id.toString().replace("-", ""))
                .setParameter("receivedAt", receivedAt)
                .executeUpdate();
    }
}

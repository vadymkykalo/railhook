package com.webhook.platform.worker.repository;

import com.webhook.platform.common.enums.ForwardAttemptStatus;
import com.webhook.platform.worker.domain.entity.IncomingForwardAttempt;
import com.webhook.platform.worker.domain.repository.IncomingForwardAttemptRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The Replay-session scoping of the claim, against a real Postgres.
 *
 * <p>Two things here cannot be checked with a mock. A Forward created by ingress carries no Replay
 * session, so the claim compares a column against a null bind: written as {@code =} it matches
 * nothing and every ordinary Forward silently fails to claim, which is why the SQL says
 * {@code IS NOT DISTINCT FROM} and why the bind carries an explicit cast to uuid. And the
 * stranded-PENDING sweep is a native UPDATE whose predicate only means anything against real rows.
 */
@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration,"
                + "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration,"
                + "org.springframework.boot.data.redis.autoconfigure.DataRedisRepositoriesAutoConfiguration"
})
class IncomingForwardAttemptRepositoryTest {

    /** {@code organization_id} is NOT NULL and the worker copies it off the parent row. */
    private static final UUID FIXTURE_ORG = UUID.randomUUID();

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("webhook_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }

    @Autowired
    private IncomingForwardAttemptRepository attemptRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void claimingAnIngressForwardMatchesTheRowThatCarriesNoReplaySession() {
        UUID eventId = UUID.randomUUID();
        UUID destinationId = UUID.randomUUID();
        persist(eventId, destinationId, 1, null, ForwardAttemptStatus.PENDING, null);
        UUID token = UUID.randomUUID();

        int claimed = attemptRepository.claimForProcessing(eventId, destinationId, 1, null, token);
        entityManager.clear();

        assertEquals(1, claimed, "a Forward with no Replay session must still be claimable");
        IncomingForwardAttempt row = only(attemptRepository.findForwardAttempts(eventId, destinationId, null));
        assertEquals(ForwardAttemptStatus.PROCESSING, row.getStatus());
        assertEquals(token, row.getClaimToken());
    }

    @Test
    void aReplayAndTheLiveLadderClaimDifferentRowsAtTheSameAttemptNumber() {
        UUID eventId = UUID.randomUUID();
        UUID destinationId = UUID.randomUUID();
        UUID session = UUID.randomUUID();
        persist(eventId, destinationId, 1, null, ForwardAttemptStatus.PENDING, null);
        persist(eventId, destinationId, 1, session, ForwardAttemptStatus.PENDING, null);

        UUID replayToken = UUID.randomUUID();
        int claimed = attemptRepository.claimForProcessing(eventId, destinationId, 1, session, replayToken);
        entityManager.clear();

        assertEquals(1, claimed, "the Replay must claim exactly its own row, not both attempt 1s");
        assertEquals(ForwardAttemptStatus.PROCESSING,
                only(attemptRepository.findForwardAttempts(eventId, destinationId, session)).getStatus());
        assertEquals(ForwardAttemptStatus.PENDING,
                only(attemptRepository.findForwardAttempts(eventId, destinationId, null)).getStatus(),
                "the ingress Forward is a different obligation and must be untouched");
    }

    @Test
    void strandedPendingForwardsAreHandedBackToTheScheduler() {
        UUID eventId = UUID.randomUUID();
        UUID destinationId = UUID.randomUUID();
        UUID stranded = persist(eventId, destinationId, 1, null, ForwardAttemptStatus.PENDING, null);
        UUID fresh = persist(eventId, UUID.randomUUID(), 1, null, ForwardAttemptStatus.PENDING, null);
        backdate(stranded, Instant.now().minus(3, ChronoUnit.HOURS));

        int recovered = attemptRepository.resetStrandedPendingForwardAttempts(
                Instant.now().minus(1, ChronoUnit.HOURS));
        entityManager.clear();

        assertEquals(1, recovered);
        assertNotNull(attemptRepository.findById(stranded).orElseThrow().getNextRetryAt(),
                "the scheduler ignores rows without a next_retry_at, so recovery must set one");
        assertNull(attemptRepository.findById(fresh).orElseThrow().getNextRetryAt(),
                "a Forward that has only just been received is still waiting for its dispatch message");
    }

    private IncomingForwardAttempt only(List<IncomingForwardAttempt> rows) {
        assertEquals(1, rows.size(), "expected exactly one attempt row, got " + rows.size());
        return rows.get(0);
    }

    private UUID persist(UUID eventId, UUID destinationId, int attemptNumber, UUID replaySessionId,
            ForwardAttemptStatus status, Instant nextRetryAt) {
        IncomingForwardAttempt attempt = IncomingForwardAttempt.builder()
                .organizationId(FIXTURE_ORG)
                .incomingEventId(eventId)
                .destinationId(destinationId)
                .attemptNumber(attemptNumber)
                .replaySessionId(replaySessionId)
                .status(status)
                .nextRetryAt(nextRetryAt)
                .build();
        entityManager.persistAndFlush(attempt);
        return attempt.getId();
    }

    /** created_at is @CreationTimestamp and not updatable, so age has to be faked in SQL. */
    private void backdate(UUID attemptId, Instant createdAt) {
        entityManager.getEntityManager()
                .createNativeQuery("UPDATE incoming_forward_attempts SET created_at = :createdAt WHERE id = :id")
                .setParameter("createdAt", createdAt)
                .setParameter("id", attemptId)
                .executeUpdate();
        entityManager.flush();
    }
}

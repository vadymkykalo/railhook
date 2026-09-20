package com.webhook.platform.worker.repository;

import com.webhook.platform.worker.attempt.OutgoingAttemptStore;
import com.webhook.platform.worker.domain.entity.Delivery;
import com.webhook.platform.worker.domain.entity.Endpoint;
import com.webhook.platform.worker.domain.repository.DeliveryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for DeliveryRepository query optimization
 * 
 * Tests:
 * 1. Query selects only PENDING deliveries with nextRetryAt <= now
 * 2. Results are ordered by nextRetryAt ASC
 * 3. Pagination works correctly (batch size limit)
 * 4. Row-level locking prevents concurrent access (PESSIMISTIC_WRITE)
 */
@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration,org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration,org.springframework.boot.data.redis.autoconfigure.DataRedisRepositoriesAutoConfiguration"
})
class DeliveryRepositoryTest {

    /**
     * Fixture tenant for persisted rows.
     *
     * <p>{@code organization_id} is NOT NULL, and the worker's entities map it
     * without filtering on it: in production the worker copies the value off the parent row it is
     * processing. A fixture that persists directly has to supply one.
     */
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
    private DeliveryRepository deliveryRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private UUID sharedEndpointId;
    private UUID sharedProjectId;

    private void createSharedEndpoint() {
        sharedProjectId = UUID.randomUUID();
        sharedEndpointId = UUID.randomUUID();
        Endpoint endpoint = Endpoint.builder()
                .organizationId(FIXTURE_ORG)
                .id(sharedEndpointId)
                .projectId(sharedProjectId)
                .url("https://example.com/hook")
                .secretEncrypted("enc")
                .secretIv("iv")
                .enabled(true)
                .build();
        entityManager.persist(endpoint);
    }

    @Test
    void findPendingRetryIds_shouldOnlySelectPendingStatus() {
        // Arrange
        createSharedEndpoint();
        Instant now = Instant.now();
        Delivery pending = createAndPersistDelivery(Delivery.DeliveryStatus.PENDING, now.minusSeconds(60));
        Delivery processing = createAndPersistDelivery(Delivery.DeliveryStatus.PROCESSING, now.minusSeconds(60));
        Delivery success = createAndPersistDelivery(Delivery.DeliveryStatus.SUCCESS, now.minusSeconds(60));
        
        entityManager.flush();
        entityManager.clear();

        // Act
        List<UUID> ids = deliveryRepository.findPendingRetryIds(
                Delivery.DeliveryStatus.PENDING, now, 10, 100, 100);
        List<Delivery> result = deliveryRepository.lockByIds(ids);

        // Assert
        assertEquals(1, result.size());
        assertEquals(pending.getId(), result.get(0).getId());
    }

    @Test
    void findPendingRetryIds_shouldOnlySelectDueRetries() {
        // Arrange
        createSharedEndpoint();
        Instant now = Instant.now();
        Delivery overdue = createAndPersistDelivery(Delivery.DeliveryStatus.PENDING, now.minusSeconds(120));
        Delivery justDue = createAndPersistDelivery(Delivery.DeliveryStatus.PENDING, now.minusSeconds(1));
        Delivery notYet = createAndPersistDelivery(Delivery.DeliveryStatus.PENDING, now.plusSeconds(3600));
        Delivery noRetry = createAndPersistDelivery(Delivery.DeliveryStatus.PENDING, null);
        
        entityManager.flush();
        entityManager.clear();

        // Act
        List<UUID> ids = deliveryRepository.findPendingRetryIds(
                Delivery.DeliveryStatus.PENDING, now, 10, 100, 100);
        List<Delivery> result = deliveryRepository.lockByIds(ids);

        // Assert
        assertEquals(2, result.size());
        assertTrue(result.stream().anyMatch(d -> d.getId().equals(overdue.getId())));
        assertTrue(result.stream().anyMatch(d -> d.getId().equals(justDue.getId())));
    }

    @Test
    void lockByIds_shouldOrderByNextRetryAtAsc() {
        // Arrange
        createSharedEndpoint();
        Instant now = Instant.now();
        Delivery third = createAndPersistDelivery(Delivery.DeliveryStatus.PENDING, now.minusSeconds(10));
        Delivery first = createAndPersistDelivery(Delivery.DeliveryStatus.PENDING, now.minusSeconds(300));
        Delivery second = createAndPersistDelivery(Delivery.DeliveryStatus.PENDING, now.minusSeconds(150));
        
        entityManager.flush();
        entityManager.clear();

        // Act
        List<UUID> ids = deliveryRepository.findPendingRetryIds(
                Delivery.DeliveryStatus.PENDING, now, 10, 100, 100);
        List<Delivery> result = deliveryRepository.lockByIds(ids);

        // Assert
        assertEquals(3, result.size());
        assertEquals(first.getId(), result.get(0).getId());
        assertEquals(second.getId(), result.get(1).getId());
        assertEquals(third.getId(), result.get(2).getId());
    }

    @Test
    void resetStrandedPendingDeliveries_shouldRecoverOldStrandedPendingRow() {
        // Reproduces the retry-claim black hole: a PENDING delivery with next_retry_at
        // wiped (the pre-fix claim contract nulled it without ever setting PROCESSING) is
        // invisible to both existing recovery mechanisms.
        createSharedEndpoint();
        Delivery stranded = createAndPersistDelivery(
                Delivery.DeliveryStatus.PENDING, null, Instant.now().minus(2, java.time.temporal.ChronoUnit.HOURS));

        entityManager.flush();
        entityManager.clear();

        List<UUID> pendingIds = deliveryRepository.findPendingRetryIds(
                Delivery.DeliveryStatus.PENDING, Instant.now(), 10, 100, 100);
        assertTrue(pendingIds.isEmpty(), "black-holed row must not be visible to findPendingRetryIds");

        int recoveredByStuckSweep = deliveryRepository.resetStuckDeliveries(Instant.now().plusSeconds(3600));
        assertEquals(0, recoveredByStuckSweep,
                "black-holed row never reached PROCESSING, so resetStuckDeliveries can't see it either");

        // Act — the belt-and-braces recovery query
        int recovered = deliveryRepository.resetStrandedPendingDeliveries(Instant.now().minusSeconds(300));

        // Assert
        assertEquals(1, recovered);
        entityManager.clear();
        Delivery reloaded = deliveryRepository.findById(stranded.getId()).orElseThrow();
        assertNotNull(reloaded.getNextRetryAt());
        assertEquals(Delivery.DeliveryStatus.PENDING, reloaded.getStatus());

        // And now it is visible to the normal retry poll again
        List<UUID> idsAfterRecovery = deliveryRepository.findPendingRetryIds(
                Delivery.DeliveryStatus.PENDING, Instant.now().plusSeconds(1), 10, 100, 100);
        assertTrue(idsAfterRecovery.contains(stranded.getId()));
    }

    @Test
    void resetStrandedPendingDeliveries_shouldNotSweepFreshlyIngestedRow() {
        // Freshly ingested deliveries are also PENDING with next_retry_at = NULL and rely
        // entirely on their one outbox Kafka message — the sweep must not touch them.
        createSharedEndpoint();
        Delivery fresh = createAndPersistDelivery(Delivery.DeliveryStatus.PENDING, null, Instant.now());

        entityManager.flush();
        entityManager.clear();

        int recovered = deliveryRepository.resetStrandedPendingDeliveries(Instant.now().minusSeconds(300));

        assertEquals(0, recovered);
        Delivery reloaded = deliveryRepository.findById(fresh.getId()).orElseThrow();
        assertNull(reloaded.getNextRetryAt());
    }

    @Test
    void claimRetryForProcessing_keepsASlowRetryOutOfTheStuckSweep() {
        // The scheduler claimed this row six minutes ago and its message sat in the retry topic
        // since. The consumer's CAS is the start of a real Attempt, so the stuck sweep must not
        // treat the row as abandoned the moment the POST goes out — if it does, the scheduler
        // re-claims it and a second request reaches the endpoint while the first is in flight.
        createSharedEndpoint();
        UUID publishedToken = UUID.randomUUID();
        Instant scheduledAt = Instant.now().minus(6, java.time.temporal.ChronoUnit.MINUTES);
        Delivery delivery = createAndPersistDelivery(Delivery.DeliveryStatus.PROCESSING, null, scheduledAt);
        delivery.setClaimToken(publishedToken);
        delivery.setLastAttemptAt(scheduledAt);
        entityManager.flush();
        entityManager.clear();

        UUID consumerToken = UUID.randomUUID();
        Delivery claimed = deliveryRepository.claimRetryForProcessing(delivery.getId(), publishedToken, consumerToken);
        assertNotNull(claimed, "the consumer holds the published token, so its CAS must apply");
        entityManager.clear();

        int swept = deliveryRepository.resetStuckDeliveries(Instant.now().minus(5, java.time.temporal.ChronoUnit.MINUTES));

        assertEquals(0, swept, "a retry claimed a moment ago is in flight, not stuck");
        Delivery reloaded = deliveryRepository.findById(delivery.getId()).orElseThrow();
        assertEquals(Delivery.DeliveryStatus.PROCESSING, reloaded.getStatus());
        assertEquals(consumerToken, reloaded.getClaimToken());
    }

    @Test
    void attemptStarting_aSweptAttemptDoesNotSpendItsSuccessorsRung() {
        // The stuck sweep took this row from an Attempt still running, and a successor has claimed
        // it since under a token of its own. The first Attempt only now reaches attemptStarting;
        // matched by id alone, its increment spent a rung of the successor's Ladder.
        createSharedEndpoint();
        Delivery delivery = createAndPersistDelivery(Delivery.DeliveryStatus.PROCESSING, null);
        UUID sweptFence = UUID.randomUUID();
        delivery.setClaimToken(UUID.randomUUID());
        entityManager.flush();
        entityManager.clear();

        OutgoingAttemptStore store = new OutgoingAttemptStore(deliveryRepository, null, null, null, null,
                new TransactionTemplate(transactionManager), null, null, null, null, null, null, null, null,
                null, null, null, 0, null, true);
        store.attemptStarting(new OutgoingAttemptStore.Claim(delivery.getId(), sweptFence, delivery));
        entityManager.clear();

        assertEquals(1, deliveryRepository.findById(delivery.getId()).orElseThrow().getAttemptCount(),
                "the rung belongs to the Attempt that holds the row now");
    }

    @Test
    void findStaleDeliveryIds_measuresAgeFromWhenTheDeliveryWasLastPutBackOnItsLadder() {
        // A Delivery keeps its created_at when a person retries it from Failed Messages. Measured
        // from created_at, one created five days ago was escalated back to DLQ at the next sweep,
        // before its new attempts had a chance to run.
        createSharedEndpoint();
        Instant now = Instant.now();
        Delivery neverResumed = persistPendingDelivery(now.minus(100, java.time.temporal.ChronoUnit.HOURS), null);
        Delivery retriedJustNow = persistPendingDelivery(
                now.minus(100, java.time.temporal.ChronoUnit.HOURS), now.minusSeconds(60));
        Delivery retriedLongAgo = persistPendingDelivery(
                now.minus(200, java.time.temporal.ChronoUnit.HOURS), now.minus(100, java.time.temporal.ChronoUnit.HOURS));
        entityManager.flush();
        entityManager.clear();

        List<UUID> stale = deliveryRepository.findStaleDeliveryIds(now.minus(96, java.time.temporal.ChronoUnit.HOURS), 10);

        assertTrue(stale.contains(neverResumed.getId()), "an old Delivery nobody touched is still escalated");
        assertFalse(stale.contains(retriedJustNow.getId()), "a Delivery retried a minute ago is not stale");
        assertTrue(stale.contains(retriedLongAgo.getId()), "a retry is not a permanent exemption from the cap");
    }

    /**
     * A failed Attempt hands the Delivery back PENDING with its next rung in next_retry_at. The
     * dispatch claim matched on status alone, so a second copy of the dispatch message — Kafka
     * redelivery after a rebalance, an outbox re-publish — took the row at once: an Attempt the
     * ladder had not reached yet, spending a rung and bringing the DLQ closer.
     */
    @Test
    void claimForProcessingAndReturn_leavesADeliveryWaitingOnItsLadderAlone() {
        createSharedEndpoint();
        Delivery waiting = createAndPersistDelivery(Delivery.DeliveryStatus.PENDING, Instant.now().plusSeconds(300));
        entityManager.flush();
        entityManager.clear();

        Delivery claimed = deliveryRepository.claimForProcessingAndReturn(waiting.getId(), UUID.randomUUID(), Instant.now());

        assertNull(claimed, "a Delivery whose next rung is in five minutes is not due for an Attempt");
    }

    @Test
    void claimForProcessingAndReturn_takesAFreshOrDueDelivery() {
        createSharedEndpoint();
        Delivery fresh = createAndPersistDelivery(Delivery.DeliveryStatus.PENDING, null);
        Delivery due = createAndPersistDelivery(Delivery.DeliveryStatus.PENDING, Instant.now().minusSeconds(5));
        entityManager.flush();
        entityManager.clear();

        assertNotNull(deliveryRepository.claimForProcessingAndReturn(fresh.getId(), UUID.randomUUID(), Instant.now()));
        assertNotNull(deliveryRepository.claimForProcessingAndReturn(due.getId(), UUID.randomUUID(), Instant.now()));
    }

    @Test
    void findPendingRetryIds_shouldRespectPageSize() {
        // Arrange
        createSharedEndpoint();
        Instant now = Instant.now();
        for (int i = 0; i < 15; i++) {
            createAndPersistDelivery(Delivery.DeliveryStatus.PENDING, now.minusSeconds(60 + i));
        }
        
        entityManager.flush();
        entityManager.clear();

        // Act
        List<UUID> ids = deliveryRepository.findPendingRetryIds(
                Delivery.DeliveryStatus.PENDING, now, 5, 100, 100);

        // Assert
        assertEquals(5, ids.size());
    }

    private Delivery persistPendingDelivery(Instant createdAt, Instant ladderResumedAt) {
        return entityManager.persist(Delivery.builder()
                .organizationId(FIXTURE_ORG)
                .id(UUID.randomUUID())
                .eventId(UUID.randomUUID())
                .endpointId(sharedEndpointId)
                .subscriptionId(UUID.randomUUID())
                .status(Delivery.DeliveryStatus.PENDING)
                .attemptCount(7)
                .maxAttempts(10)
                .orderingEnabled(false)
                .ladderResumedAt(ladderResumedAt)
                .createdAt(createdAt)
                .updatedAt(ladderResumedAt != null ? ladderResumedAt : createdAt)
                .build());
    }

    /**
     * What the ordering gate asks before it breaks the order: is anything still outstanding in
     * the gap going to be attempted? A Delivery between the rungs of its ladder is, and its
     * successors must keep waiting for it however long they have already waited — the gap
     * timeout is for a gap that never closes.
     */
    @Test
    void countGapClosingBefore_countsWhatIsInFlightOrDueAndNothingElse() {
        createSharedEndpoint();
        Instant now = Instant.now();

        Delivery dueSoon = orderedDelivery(3L, Delivery.DeliveryStatus.PENDING, now.plusSeconds(20), now);
        Delivery inFlight = orderedDelivery(4L, Delivery.DeliveryStatus.PROCESSING, null, now.minusSeconds(5));
        // A later rung: nothing will touch this one for an hour.
        orderedDelivery(5L, Delivery.DeliveryStatus.PENDING, now.plusSeconds(3600), now);
        // Claimed, then abandoned by whoever held it: PROCESSING, but nobody is attempting it.
        orderedDelivery(6L, Delivery.DeliveryStatus.PROCESSING, null, now.minusSeconds(600));
        // Resolved, so not outstanding at all.
        orderedDelivery(7L, Delivery.DeliveryStatus.SUCCESS, null, now);
        // Due, but outside the gap being asked about.
        orderedDelivery(20L, Delivery.DeliveryStatus.PENDING, now, now);

        entityManager.flush();
        entityManager.clear();

        long closing = deliveryRepository.countGapClosingBefore(sharedEndpointId, 3L, 7L,
                now.minusSeconds(60), now.plusSeconds(60));

        assertEquals(2, closing,
                "only the Delivery due inside the window (" + dueSoon.getSequenceNumber()
                        + ") and the one being attempted now (" + inFlight.getSequenceNumber() + ") count");
    }

    /**
     * The converse, and the reason the gap timeout still exists: with nothing in the gap due and
     * nothing being attempted, waiting is futile and the successors are let through.
     */
    @Test
    void countGapClosingBefore_nothingDueOrInFlight_isZero() {
        createSharedEndpoint();
        Instant now = Instant.now();

        orderedDelivery(3L, Delivery.DeliveryStatus.PENDING, now.plusSeconds(21600), now);
        orderedDelivery(4L, Delivery.DeliveryStatus.PROCESSING, null, now.minusSeconds(3600));

        entityManager.flush();
        entityManager.clear();

        assertEquals(0, deliveryRepository.countGapClosingBefore(sharedEndpointId, 3L, 4L,
                now.minusSeconds(60), now.plusSeconds(60)));
    }

    private Delivery orderedDelivery(long sequenceNumber, Delivery.DeliveryStatus status,
            Instant nextRetryAt, Instant updatedAt) {
        Delivery delivery = Delivery.builder()
                .organizationId(FIXTURE_ORG)
                .id(UUID.randomUUID())
                .eventId(UUID.randomUUID())
                .endpointId(sharedEndpointId)
                .subscriptionId(UUID.randomUUID())
                .status(status)
                .attemptCount(1)
                .maxAttempts(7)
                .orderingEnabled(true)
                .sequenceNumber(sequenceNumber)
                .nextRetryAt(nextRetryAt)
                .createdAt(Instant.now())
                .updatedAt(updatedAt)
                .build();
        return entityManager.persist(delivery);
    }

    private Delivery createAndPersistDelivery(Delivery.DeliveryStatus status, Instant nextRetryAt) {
        return createAndPersistDelivery(status, nextRetryAt, Instant.now());
    }

    private Delivery createAndPersistDelivery(Delivery.DeliveryStatus status, Instant nextRetryAt, Instant updatedAt) {
        Delivery delivery = Delivery.builder()
                .organizationId(FIXTURE_ORG)
                .id(UUID.randomUUID())
                .eventId(UUID.randomUUID())
                .endpointId(sharedEndpointId)
                .subscriptionId(UUID.randomUUID())
                .status(status)
                .attemptCount(1)
                .maxAttempts(7)
                .orderingEnabled(false)
                .nextRetryAt(nextRetryAt)
                .createdAt(Instant.now())
                .updatedAt(updatedAt)
                .build();

        return entityManager.persist(delivery);
    }
}

package com.webhook.platform.api;

import com.webhook.platform.api.domain.entity.OutboxMessage;
import com.webhook.platform.api.domain.enums.OutboxStatus;
import com.webhook.platform.api.domain.repository.OutboxMessageRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Both native queries below partition on {@code COALESCE(project_id::text, kafka_key)}.
 * Hibernate's native-query parameter parser treats {@code :text} as a named
 * parameter placeholder — it doesn't understand Postgres's {@code ::} cast —
 * so this must run against a real Postgres via Hibernate, not be asserted as
 * a string. A mocked repository (as in OutboxPublisherServiceTest) can't
 * catch this class of bug.
 */
public class OutboxMessageRepositoryTest extends AbstractIntegrationTest {

    @Autowired
    private OutboxMessageRepository outboxMessageRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private EntityManager entityManager;

    /**
     * The bulk updates below are {@code @Modifying} and need a transaction, exactly as they get
     * one in {@code OutboxPublisherService} — which uses a TransactionTemplate rather than
     * {@code @Transactional} so that self-invocation cannot bypass the proxy. Same here, and for
     * the same reason it has to be a real one: these assertions are about what Postgres did.
     */
    private int inTransaction(java.util.function.IntSupplier update) {
        int rows = new TransactionTemplate(transactionManager).execute(tx -> update.getAsInt());
        entityManager.clear();
        return rows;
    }

    @Test
    void findPendingBatchForUpdate_shouldReturnRowWithProjectId() {
        OutboxMessage message = outboxMessageRepository.save(pendingMessage(UUID.randomUUID()));

        List<OutboxMessage> batch = outboxMessageRepository.findPendingBatchForUpdate(
                OutboxStatus.PENDING.name(), 100, 10, 30);

        assertTrue(batch.stream().anyMatch(m -> m.getId().equals(message.getId())));
    }

    @Test
    void findPendingBatchForUpdate_shouldReturnRowWithNullProjectId() {
        // COALESCE's fallback arm (kafka_key) — the ingress/incoming path can
        // write outbox rows with no project_id.
        OutboxMessage message = outboxMessageRepository.save(pendingMessage(null));

        List<OutboxMessage> batch = outboxMessageRepository.findPendingBatchForUpdate(
                OutboxStatus.PENDING.name(), 100, 10, 30);

        assertTrue(batch.stream().anyMatch(m -> m.getId().equals(message.getId())));
    }

    @Test
    void findFailedMessagesForRetry_shouldReturnRowBelowMaxRetries() {
        OutboxMessage failed = pendingMessage(UUID.randomUUID());
        failed.setStatus(OutboxStatus.FAILED);
        failed.setRetryCount(1);
        OutboxMessage saved = outboxMessageRepository.save(failed);

        List<OutboxMessage> batch = outboxMessageRepository.findFailedMessagesForRetry(
                OutboxStatus.FAILED.name(), 5, 100, 10, 30);

        assertEquals(1, batch.stream().filter(m -> m.getId().equals(saved.getId())).count());
    }

    @Test
    void recoverStuckSendingMessages_countsTheAttemptItIsUndoing() {
        // Without this the row cycles PENDING -> SENDING -> PENDING for ever. Recovery is the
        // only path out of SENDING, promoteExhaustedToDead only looks at FAILED, and nothing
        // else touches retry_count — so a message that never gets a callback inside
        // batchSendTimeoutSeconds is immortal, and nothing says so beyond a queue-depth gauge.
        // Not an exotic case: that timeout is 30s and Kafka's own delivery.timeout.ms is 120s.
        OutboxMessage stuck = pendingMessage(UUID.randomUUID());
        stuck.setStatus(OutboxStatus.SENDING);
        OutboxMessage saved = outboxMessageRepository.saveAndFlush(stuck);

        int recovered = inTransaction(() -> outboxMessageRepository.recoverStuckSendingMessages(
                Instant.now().plus(1, ChronoUnit.MINUTES)));

        assertEquals(1, recovered);
        OutboxMessage after = outboxMessageRepository.findById(saved.getId()).orElseThrow();
        assertEquals(OutboxStatus.PENDING, after.getStatus());
        assertEquals(1, after.getRetryCount(), "a recovery is an attempt that did not land");
    }

    @Test
    void batchMarkPublished_leavesARowSomebodyElseHasReclaimed() {
        // A Kafka callback that arrives after the batch wait is not discarded — it lands in the
        // next cycle's update. By then the row it names may have been recovered to PENDING and
        // re-claimed as SENDING by a later cycle, and stamping the old outcome onto it loses
        // whatever that cycle was doing. Only a row this batch still holds may be settled.
        OutboxMessage reclaimed = pendingMessage(UUID.randomUUID());
        reclaimed.setStatus(OutboxStatus.PENDING);
        OutboxMessage saved = outboxMessageRepository.saveAndFlush(reclaimed);

        int updated = inTransaction(() ->
                outboxMessageRepository.batchMarkPublished(List.of(saved.getId()), Instant.now()));

        assertEquals(0, updated);
        assertEquals(OutboxStatus.PENDING,
                outboxMessageRepository.findById(saved.getId()).orElseThrow().getStatus());
    }

    @Test
    void batchMarkFailed_likewise() {
        OutboxMessage reclaimed = pendingMessage(UUID.randomUUID());
        reclaimed.setStatus(OutboxStatus.PENDING);
        OutboxMessage saved = outboxMessageRepository.saveAndFlush(reclaimed);

        int updated = inTransaction(() -> outboxMessageRepository.batchMarkFailed(
                List.of(saved.getId()), "a straggler from a previous cycle", Instant.now()));

        assertEquals(0, updated);
        assertEquals(OutboxStatus.PENDING,
                outboxMessageRepository.findById(saved.getId()).orElseThrow().getStatus());
    }

    @Test
    void batchMarkPublished_stillSettlesARowThisBatchHolds() {
        OutboxMessage sending = pendingMessage(UUID.randomUUID());
        sending.setStatus(OutboxStatus.SENDING);
        OutboxMessage saved = outboxMessageRepository.saveAndFlush(sending);

        int updated = inTransaction(() ->
                outboxMessageRepository.batchMarkPublished(List.of(saved.getId()), Instant.now()));

        assertEquals(1, updated);
        assertEquals(OutboxStatus.PUBLISHED,
                outboxMessageRepository.findById(saved.getId()).orElseThrow().getStatus());
    }

    private OutboxMessage pendingMessage(UUID projectId) {
        return OutboxMessage.builder()
                .aggregateType("Event")
                .aggregateId(UUID.randomUUID())
                .eventType("test.event")
                .payload("{}")
                .kafkaTopic("events.dispatch")
                .kafkaKey(UUID.randomUUID().toString())
                .projectId(projectId)
                .status(OutboxStatus.PENDING)
                .retryCount(0)
                .build();
    }
}

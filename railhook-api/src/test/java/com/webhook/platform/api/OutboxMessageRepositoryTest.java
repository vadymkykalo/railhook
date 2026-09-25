package com.webhook.platform.api;

import com.webhook.platform.api.domain.entity.OutboxMessage;
import com.webhook.platform.api.domain.enums.OutboxStatus;
import com.webhook.platform.api.domain.repository.OutboxMessageRepository;
import com.webhook.platform.api.service.OutboxPublisherService;
import com.webhook.platform.api.tenancy.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

// Hibernate parses ::text as a named parameter, so this must run against real Postgres.
public class OutboxMessageRepositoryTest extends AbstractIntegrationTest {

    @Autowired
    private OutboxMessageRepository outboxMessageRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private EntityManager entityManager;

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
        // No project_id: the ingress path writes outbox rows keyed only by kafka_key.
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
        // Otherwise the row cycles PENDING -> SENDING -> PENDING forever.
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
    void aRowThatNeverGetsASendOutcomeStopsCyclingOnceItsRetriesAreSpent() {
        int maxRetries = 5;
        OutboxPublisherService publisher = new OutboxPublisherService(
                outboxMessageRepository, mock(KafkaTemplate.class),
                new ObjectMapper(), new SimpleMeterRegistry(),
                transactionManager, 100, maxRetries, 90, 0, 1, 30, 10);

        OutboxMessage lastChance = pendingMessage(UUID.randomUUID());
        lastChance.setStatus(OutboxStatus.SENDING);
        lastChance.setRetryCount(maxRetries - 1);
        OutboxMessage exhausted = outboxMessageRepository.saveAndFlush(lastChance);

        OutboxMessage firstTime = pendingMessage(UUID.randomUUID());
        firstTime.setStatus(OutboxStatus.SENDING);
        OutboxMessage recoverable = outboxMessageRepository.saveAndFlush(firstTime);

        TenantContext.runAsSystem(publisher::retryFailedMessages);
        entityManager.clear();

        assertEquals(OutboxStatus.DEAD,
                outboxMessageRepository.findById(exhausted.getId()).orElseThrow().getStatus());
        assertEquals(OutboxStatus.PENDING,
                outboxMessageRepository.findById(recoverable.getId()).orElseThrow().getStatus());
    }

    @Test
    void batchMarkPublished_leavesARowSomebodyElseHasReclaimed() {
        // A late Kafka callback must not settle a row a later cycle has re-claimed.
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

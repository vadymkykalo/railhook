package com.webhook.platform.api;

import com.webhook.platform.api.domain.entity.OutboxMessage;
import com.webhook.platform.api.domain.enums.OutboxStatus;
import com.webhook.platform.api.domain.repository.OutboxMessageRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

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

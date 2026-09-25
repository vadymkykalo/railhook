package com.webhook.platform.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.domain.entity.OutboxMessage;
import com.webhook.platform.api.domain.enums.OutboxStatus;
import com.webhook.platform.api.domain.repository.OutboxMessageRepository;
import com.webhook.platform.api.service.OutboxPublisherService;
import com.webhook.platform.api.tenancy.TenantContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

public class OutboxQueueDepthGaugeIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private OutboxMessageRepository outboxMessageRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @SuppressWarnings("unchecked")
    void countsEveryOrganizationsMessagesWhenScrapedWithoutATenantScope() {
        outboxMessageRepository.save(message(OutboxStatus.PENDING));
        outboxMessageRepository.save(message(OutboxStatus.PENDING));
        outboxMessageRepository.save(message(OutboxStatus.DEAD));
        long pending = outboxMessageRepository.countByStatus(OutboxStatus.PENDING);
        long dead = outboxMessageRepository.countByStatus(OutboxStatus.DEAD);

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new OutboxPublisherService(outboxMessageRepository, mock(KafkaTemplate.class), new ObjectMapper(),
                registry, transactionManager, 100, 5, 90, 300, 30, 30, 10);

        TenantContext.clear();

        assertThat(registry.get("outbox_queue_depth").tag("status", "pending").gauge().value())
                .isEqualTo((double) pending);
        assertThat(registry.get("outbox_queue_depth").tag("status", "dead").gauge().value())
                .isEqualTo((double) dead);
        assertThat(TenantContext.current()).as("reading the gauge leaves no scope behind").isNull();
    }

    private OutboxMessage message(OutboxStatus status) {
        return OutboxMessage.builder()
                .aggregateType("Event")
                .aggregateId(UUID.randomUUID())
                .eventType("test.event")
                .payload("{}")
                .kafkaTopic("events.dispatch")
                .kafkaKey(UUID.randomUUID().toString())
                .projectId(UUID.randomUUID())
                .status(status)
                .retryCount(0)
                .build();
    }
}

package com.webhook.platform.worker.service;

import com.webhook.platform.common.dto.IncomingForwardMessage;
import com.webhook.platform.common.enums.ForwardAttemptStatus;
import com.webhook.platform.worker.domain.entity.IncomingForwardAttempt;
import com.webhook.platform.worker.domain.repository.IncomingForwardAttemptRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A Forward the consumer has already finished is not the scheduler's to hand back.
 *
 * <p>A retry send the scheduler could not confirm may still land. The consumer then claims the
 * row and finalises it, while the scheduler still holds its Phase 1 snapshot — PROCESSING, no
 * token. Writing that snapshot back put the row PENDING again, so the scheduler picked it up and
 * the Destination received the webhook a second time. {@code IncomingForwardAttempt} carries no
 * version, so nothing noticed.
 *
 * <p>Real Postgres, real transactions: the overwrite only exists across commits.
 */
@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration,org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration,org.springframework.boot.data.redis.autoconfigure.DataRedisRepositoriesAutoConfiguration"
})
class IncomingForwardRetrySchedulerHandBackConcurrencyTest {

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
    private PlatformTransactionManager transactionManager;

    @Test
    @SuppressWarnings("unchecked")
    void aForwardTheConsumerAlreadyFinishedIsNotHandedBack() {
        IncomingForwardAttempt lateSend = persistDueAttempt();
        IncomingForwardAttempt failedSend = persistDueAttempt();
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        KafkaTemplate<String, IncomingForwardMessage> kafka = mock(KafkaTemplate.class);
        // The send is reported as failed, but it did reach the consumer, which claimed the row
        // on the scheduler's started_at and delivered it before the scheduler looked at the result.
        when(kafka.send(anyString(), eq(lateSend.getDestinationId().toString()), any(IncomingForwardMessage.class)))
                .thenAnswer(invocation -> {
                    IncomingForwardMessage message = invocation.getArgument(2);
                    UUID consumerToken = UUID.randomUUID();
                    tx.execute(status -> attemptRepository.claimRetryForProcessing(
                            message.getIncomingEventId(), message.getDestinationId(), message.getAttemptCount(),
                            message.getReplaySessionId(), message.getStartedAt(), consumerToken));
                    tx.executeWithoutResult(status -> {
                        IncomingForwardAttempt row = attemptRepository.findById(lateSend.getId()).orElseThrow();
                        row.setStatus(ForwardAttemptStatus.SUCCESS);
                        row.setFinishedAt(Instant.now());
                        row.setResponseCode(200);
                        attemptRepository.save(row);
                    });
                    return failed();
                });
        when(kafka.send(anyString(), eq(failedSend.getDestinationId().toString()), any(IncomingForwardMessage.class)))
                .thenAnswer(invocation -> failed());

        IncomingForwardRetryScheduler scheduler = new IncomingForwardRetryScheduler(
                attemptRepository, kafka, tx, new SimpleMeterRegistry(), 50, 10, 5000L, 10000L);

        scheduler.pollPendingRetries(0);

        IncomingForwardAttempt late = attemptRepository.findById(lateSend.getId()).orElseThrow();
        assertThat(late.getStatus())
                .as("the delivered Forward stays delivered, or the scheduler sends it again")
                .isEqualTo(ForwardAttemptStatus.SUCCESS);
        assertThat(late.getResponseCode()).isEqualTo(200);

        IncomingForwardAttempt failed = attemptRepository.findById(failedSend.getId()).orElseThrow();
        assertThat(failed.getStatus())
                .as("the refused send is still handed back to its ladder")
                .isEqualTo(ForwardAttemptStatus.PENDING);
        assertThat(failed.getNextRetryAt()).isAfter(Instant.now());
        assertThat(failed.getStartedAt()).isNull();
    }

    private static CompletableFuture<SendResult<String, IncomingForwardMessage>> failed() {
        CompletableFuture<SendResult<String, IncomingForwardMessage>> refused = new CompletableFuture<>();
        refused.completeExceptionally(new RuntimeException("broker unavailable"));
        return refused;
    }

    /** Own Destination each: the candidate query caps rows per Destination. */
    private IncomingForwardAttempt persistDueAttempt() {
        return attemptRepository.saveAndFlush(IncomingForwardAttempt.builder()
                .organizationId(FIXTURE_ORG)
                .incomingEventId(UUID.randomUUID())
                .destinationId(UUID.randomUUID())
                .attemptNumber(2)
                .status(ForwardAttemptStatus.PENDING)
                .nextRetryAt(Instant.now().minusSeconds(30))
                .build());
    }
}

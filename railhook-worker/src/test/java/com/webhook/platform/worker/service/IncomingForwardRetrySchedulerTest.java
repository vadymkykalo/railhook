package com.webhook.platform.worker.service;

import com.webhook.platform.common.constants.KafkaTopics;
import com.webhook.platform.common.dto.IncomingForwardMessage;
import com.webhook.platform.common.enums.ForwardAttemptStatus;
import com.webhook.platform.worker.domain.entity.IncomingForwardAttempt;
import com.webhook.platform.worker.domain.repository.IncomingForwardAttemptRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IncomingForwardRetrySchedulerTest {

    @Mock
    private IncomingForwardAttemptRepository attemptRepository;
    @Mock
    private KafkaTemplate<String, IncomingForwardMessage> kafkaTemplate;
    @Mock
    private TransactionTemplate transactionTemplate;

    private IncomingForwardRetryScheduler scheduler;

    private final int batchSize = 50;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            var callback = invocation.getArgument(0, TransactionCallback.class);
            return callback.doInTransaction(null);
        });
        lenient().doAnswer(invocation -> {
            Consumer<Object> callback = invocation.getArgument(0, Consumer.class);
            callback.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        scheduler = new IncomingForwardRetryScheduler(
                attemptRepository, kafkaTemplate, transactionTemplate, new SimpleMeterRegistry(),
                batchSize, 10, 5000L, 10000L);
    }

    private IncomingForwardAttempt pendingAttempt(UUID id) {
        return IncomingForwardAttempt.builder()
                .id(id)
                .incomingEventId(UUID.randomUUID())
                .destinationId(UUID.randomUUID())
                .attemptNumber(1)
                .status(ForwardAttemptStatus.PENDING)
                .build();
    }

    @SuppressWarnings("unchecked")
    private SendResult<String, IncomingForwardMessage> mockSendResult() {
        SendResult<String, IncomingForwardMessage> sendResult = mock(SendResult.class);
        RecordMetadata metadata = new RecordMetadata(new TopicPartition("incoming.forward.retry", 0), 0, 0, 0, 0, 0);
        when(sendResult.getRecordMetadata()).thenReturn(metadata);
        return sendResult;
    }

    @Test
    void pollPendingRetries_claimsAndDispatchesSuccessfully() {
        UUID attemptId = UUID.randomUUID();
        IncomingForwardAttempt attempt = pendingAttempt(attemptId);

        when(attemptRepository.findPendingRetryIds(any(), any(), anyInt(),
                anyInt())).thenReturn(List.of(attemptId));
        when(attemptRepository.lockByIds(anyList())).thenReturn(List.of(attempt));

        CompletableFuture<SendResult<String, IncomingForwardMessage>> future =
                CompletableFuture.completedFuture(mockSendResult());
        when(kafkaTemplate.send(anyString(), anyString(), any(IncomingForwardMessage.class))).thenReturn(future);

        scheduler.pollPendingRetries(0);

        verify(kafkaTemplate).send(eq(
                KafkaTopics.INCOMING_FORWARD_RETRY),
                anyString(), any(IncomingForwardMessage.class));

        // A dispatched row belongs to the consumer; re-saving the claim snapshot reset its fencing token.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<IncomingForwardAttempt>> captor = ArgumentCaptor.forClass(List.class);
        verify(attemptRepository, times(1)).saveAll(captor.capture());
        List<IncomingForwardAttempt> claimSave = captor.getValue();
        assertEquals(ForwardAttemptStatus.PROCESSING, claimSave.get(0).getStatus());
        assertNotNull(claimSave.get(0).getStartedAt(), "Phase 1 must stamp the fencing token");
    }

    @Test
    void pollPendingRetries_kafkaSendFails_revertsToPendingWithJitteredRetry() {
        UUID attemptId = UUID.randomUUID();
        IncomingForwardAttempt attempt = pendingAttempt(attemptId);

        when(attemptRepository.findPendingRetryIds(any(), any(), anyInt(),
                anyInt())).thenReturn(List.of(attemptId));
        when(attemptRepository.lockByIds(anyList())).thenReturn(List.of(attempt));

        CompletableFuture<SendResult<String, IncomingForwardMessage>> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("Kafka down"));
        when(kafkaTemplate.send(anyString(), anyString(), any(IncomingForwardMessage.class))).thenReturn(failedFuture);

        Instant before = Instant.now();
        scheduler.pollPendingRetries(0);

        verify(attemptRepository, times(1)).saveAll(anyList());
        ArgumentCaptor<Instant> retryAt = ArgumentCaptor.forClass(Instant.class);
        verify(attemptRepository).handBackSchedulerClaim(
                eq(attemptId),
                eq(attempt.getStartedAt()),
                retryAt.capture());
        assertNotNull(attempt.getStartedAt());
        assertTrue(retryAt.getValue().isAfter(before),
                "a failed send must be rescheduled into the future, not left null");
    }

    @Test
    void pollPendingRetries_governorInCooldown_skipsClaimEntirely() {
        when(attemptRepository.findPendingRetryIds(any(), any(), anyInt(),
                anyInt())).thenAnswer(inv -> List.of(UUID.randomUUID()));
        when(attemptRepository.lockByIds(anyList())).thenAnswer(inv -> {
            List<UUID> ids = inv.getArgument(0);
            return List.of(pendingAttempt(ids.get(0)));
        });
        CompletableFuture<SendResult<String, IncomingForwardMessage>> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("Kafka down"));
        when(kafkaTemplate.send(anyString(), anyString(), any(IncomingForwardMessage.class))).thenReturn(failedFuture);

        for (int i = 0; i < 3; i++) {
            scheduler.pollPendingRetries(0);
        }
        verify(attemptRepository, times(3)).findPendingRetryIds(any(), any(),
                anyInt(), anyInt());

        scheduler.pollPendingRetries(0);
        verify(attemptRepository, times(3)).findPendingRetryIds(any(), any(),
                anyInt(), anyInt());
    }
}

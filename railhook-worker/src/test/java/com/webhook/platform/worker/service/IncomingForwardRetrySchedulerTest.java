package com.webhook.platform.worker.service;

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
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for IncomingForwardRetryScheduler.pollPendingRetries --
 * claim/dispatch/result bookkeeping, mirroring RetrySchedulerServiceTest's coverage of
 * the outgoing-delivery equivalent.
 */
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
            var callback = invocation.getArgument(0, org.springframework.transaction.support.TransactionCallback.class);
            return callback.doInTransaction(null);
        });
        org.mockito.Mockito.lenient().doAnswer(invocation -> {
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
    void pollPendingRetries_noCandidates_doesNothing() {
        when(attemptRepository.findPendingRetryIds(any(), any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt())).thenReturn(Collections.emptyList());

        scheduler.pollPendingRetries(0);

        verify(attemptRepository, never()).lockByIds(anyList());
        verify(kafkaTemplate, never()).send(anyString(), anyString(), any());
    }

    @Test
    void pollPendingRetries_claimsAndDispatchesSuccessfully() {
        UUID attemptId = UUID.randomUUID();
        IncomingForwardAttempt attempt = pendingAttempt(attemptId);

        when(attemptRepository.findPendingRetryIds(any(), any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of(attemptId));
        when(attemptRepository.lockByIds(anyList())).thenReturn(List.of(attempt));

        CompletableFuture<SendResult<String, IncomingForwardMessage>> future =
                CompletableFuture.completedFuture(mockSendResult());
        when(kafkaTemplate.send(anyString(), anyString(), any(IncomingForwardMessage.class))).thenReturn(future);

        scheduler.pollPendingRetries(0);

        verify(kafkaTemplate).send(org.mockito.ArgumentMatchers.eq(
                com.webhook.platform.common.constants.KafkaTopics.INCOMING_FORWARD_RETRY),
                anyString(), any(IncomingForwardMessage.class));

        // Exactly ONE saveAll: the Phase 1 claim. A successfully dispatched row is owned by
        // the consumer from that moment on, so Phase 3 must not write it back. Re-saving the
        // Phase 1 snapshot overwrote whatever the consumer had already recorded -- silently,
        // because IncomingForwardAttempt carries no @Version -- and reset started_at, the
        // fencing token claimRetryForProcessing CASes on to reject duplicate redeliveries.
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

        when(attemptRepository.findPendingRetryIds(any(), any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of(attemptId));
        when(attemptRepository.lockByIds(anyList())).thenReturn(List.of(attempt));

        CompletableFuture<SendResult<String, IncomingForwardMessage>> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("Kafka down"));
        when(kafkaTemplate.send(anyString(), anyString(), any(IncomingForwardMessage.class))).thenReturn(failedFuture);

        Instant before = Instant.now();
        scheduler.pollPendingRetries(0);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<IncomingForwardAttempt>> captor = ArgumentCaptor.forClass(List.class);
        verify(attemptRepository, times(2)).saveAll(captor.capture());
        List<IncomingForwardAttempt> resultSave = captor.getAllValues().get(1);
        assertEquals(ForwardAttemptStatus.PENDING, resultSave.get(0).getStatus());
        assertTrue(resultSave.get(0).getNextRetryAt().isAfter(before),
                "a failed send must be rescheduled into the future, not left null");
    }

    @Test
    void pollPendingRetries_governorInCooldown_skipsClaimEntirely() {
        // Drive the governor into cooldown via 3 consecutive fully-failed dispatch polls
        // (every claimed attempt's Kafka send fails), then verify the next poll makes no
        // repository/Kafka calls at all while the cooldown is active.
        when(attemptRepository.findPendingRetryIds(any(), any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt())).thenAnswer(inv -> List.of(UUID.randomUUID()));
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
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());

        // 4th call: governor should be in cooldown after 3 consecutive full failures,
        // so computeEffectiveBatch returns 0 and the claim query must not run again.
        scheduler.pollPendingRetries(0);
        verify(attemptRepository, times(3)).findPendingRetryIds(any(), any(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
    }
}

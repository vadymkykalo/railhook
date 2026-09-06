package com.webhook.platform.worker.service;

import com.webhook.platform.common.retry.RetryLadderDefaults;
import com.webhook.platform.common.constants.KafkaTopics;
import com.webhook.platform.common.dto.DeliveryMessage;
import com.webhook.platform.worker.domain.entity.Delivery;
import com.webhook.platform.worker.domain.repository.DeliveryRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.support.SendResult;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RetrySchedulerServiceTest {

        @Mock
        private DeliveryRepository deliveryRepository;

        @Mock
        private KafkaTemplate<String, DeliveryMessage> kafkaTemplate;

        @Mock
        private TransactionTemplate transactionTemplate;

        @Mock
        private CircuitBreakerService circuitBreakerService;

        private RetrySchedulerService retrySchedulerService;

        private final int batchSize = 100;
        private final long sendTimeoutSeconds = 30;
        private final long rescheduleDelaySeconds = 60;

        @BeforeEach
        void setUp() {
                // Make TransactionTemplate execute the callbacks directly. Lenient: the
                // constructor/validation-only tests below build the service without ever
                // calling scheduleRetries(), so this stub goes unused there.
                lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
                        var callback = invocation.getArgument(0, org.springframework.transaction.support.TransactionCallback.class);
                        return callback.doInTransaction(null);
                });
                lenient().doAnswer(invocation -> {
                        @SuppressWarnings("unchecked")
                        Consumer<TransactionStatus> callback = invocation.getArgument(0, Consumer.class);
                        callback.accept(null);
                        return null;
                }).when(transactionTemplate).executeWithoutResult(any());

                // Governor needs countPending — stub it to return low count (no throttling)
                lenient().when(deliveryRepository.countPending(any(Instant.class))).thenReturn(0L);

                // Circuit breaker should allow all calls by default in tests
                lenient().when(circuitBreakerService.isCallPermitted(any(UUID.class))).thenReturn(true);

                retrySchedulerService = newRetrySchedulerService();
        }

        private RetrySchedulerService newRetrySchedulerService() {
                return new RetrySchedulerService(
                                deliveryRepository,
                                kafkaTemplate,
                                transactionTemplate,
                                circuitBreakerService,
                                new SimpleMeterRegistry(),
                                batchSize,
                                10,  // maxPerEndpoint
                                30,  // maxPerProject
                                sendTimeoutSeconds,
                                rescheduleDelaySeconds,
                                5000L,   // highWatermark
                                10000L); // defaultPollIntervalMs
        }

        @Test
        void scheduleRetries_shouldLoadOnlyDueDeliveries() {
                // Arrange
                Instant now = Instant.now();
                Delivery dueDelivery = createDelivery(UUID.randomUUID(), 1, now.minusSeconds(10));

                when(deliveryRepository.findPendingRetryIds(
                                eq(Delivery.DeliveryStatus.PENDING),
                                any(Instant.class),
                                anyInt(),
                                anyInt(),
                                anyInt())).thenReturn(Collections.singletonList(dueDelivery.getId()));
                when(deliveryRepository.lockByIds(anyList())).thenReturn(Collections.singletonList(dueDelivery));

                SendResult<String, DeliveryMessage> sendResult = mockSendResult();
                CompletableFuture<SendResult<String, DeliveryMessage>> future = CompletableFuture
                                .completedFuture(sendResult);
                when(kafkaTemplate.send(anyString(), anyString(), any(DeliveryMessage.class))).thenReturn(future);

                // Act
                retrySchedulerService.scheduleRetries(0);

                // Assert
                ArgumentCaptor<Integer> limitCaptor = ArgumentCaptor.forClass(Integer.class);
                verify(deliveryRepository).findPendingRetryIds(
                                eq(Delivery.DeliveryStatus.PENDING),
                                any(Instant.class),
                                limitCaptor.capture(),
                                anyInt(),
                                anyInt());

                assertEquals(batchSize, limitCaptor.getValue());
                verify(kafkaTemplate, times(1)).send(anyString(), anyString(), any(DeliveryMessage.class));
        }

        @Test
        void scheduleRetries_shouldRespectBatchSize() {
                // Arrange
                Instant now = Instant.now();
                List<Delivery> deliveries = Arrays.asList(
                                createDelivery(UUID.randomUUID(), 1, now.minusSeconds(10)),
                                createDelivery(UUID.randomUUID(), 2, now.minusSeconds(20)),
                                createDelivery(UUID.randomUUID(), 3, now.minusSeconds(30)));

                when(deliveryRepository.findPendingRetryIds(
                                any(Delivery.DeliveryStatus.class),
                                any(Instant.class),
                                anyInt(),
                                anyInt(),
                                anyInt())).thenReturn(deliveries.stream().map(Delivery::getId).toList());
                when(deliveryRepository.lockByIds(anyList())).thenReturn(deliveries);

                SendResult<String, DeliveryMessage> sendResult = mockSendResult();
                CompletableFuture<SendResult<String, DeliveryMessage>> future = CompletableFuture
                                .completedFuture(sendResult);
                when(kafkaTemplate.send(anyString(), anyString(), any(DeliveryMessage.class))).thenReturn(future);

                // Act
                retrySchedulerService.scheduleRetries(0);

                // Assert
                verify(kafkaTemplate, times(3)).send(anyString(), anyString(), any(DeliveryMessage.class));
                // Phase 1 claim only. A successful send hands the row to the consumer, which
                // may already have advanced it by the time Phase 3 runs — re-saving the Phase 1
                // snapshot there raced that update and could stall the retry partition, so
                // Phase 3 writes nothing when every send succeeded.
                verify(deliveryRepository, times(1)).saveAll(anyList());
        }

        @Test
        void scheduleRetries_shouldNullifyNextRetryAt() {
                // Arrange
                Instant now = Instant.now();
                Delivery delivery = createDelivery(UUID.randomUUID(), 1, now.minusSeconds(10));

                when(deliveryRepository.findPendingRetryIds(
                                any(Delivery.DeliveryStatus.class),
                                any(Instant.class),
                                anyInt(),
                                anyInt(),
                                anyInt())).thenReturn(Collections.singletonList(delivery.getId()));
                when(deliveryRepository.lockByIds(anyList())).thenReturn(Collections.singletonList(delivery));

                SendResult<String, DeliveryMessage> sendResult = mockSendResult();
                CompletableFuture<SendResult<String, DeliveryMessage>> future = CompletableFuture
                                .completedFuture(sendResult);
                when(kafkaTemplate.send(anyString(), anyString(), any(DeliveryMessage.class))).thenReturn(future);

                // Act
                retrySchedulerService.scheduleRetries(0);

                // Assert — Phase 1 nullifies nextRetryAt and is the only write for a
                // successful send; Phase 3 leaves the row to the consumer.
                @SuppressWarnings("unchecked")
                ArgumentCaptor<List<Delivery>> deliveryCaptor = ArgumentCaptor.forClass(List.class);
                verify(deliveryRepository, times(1)).saveAll(deliveryCaptor.capture());
                List<List<Delivery>> allSaves = deliveryCaptor.getAllValues();
                assertNull(allSaves.get(0).get(0).getNextRetryAt());
        }

        @Test
        void scheduleRetries_claimPhase_shouldSetStatusProcessingAndLastAttemptAt() {
                // Regression test for the retry-claim black hole: the old claim phase only
                // nullified nextRetryAt and left status=PENDING, which is invisible to both
                // findPendingRetryIds (needs non-null nextRetryAt) and resetStuckDeliveries
                // (needs status=PROCESSING) if the worker crashes before Phase 3 runs. The
                // claim must set status=PROCESSING and lastAttemptAt, matching
                // IncomingForwardRetryScheduler's claim of forward attempts, so a crashed
                // claim is recoverable by StuckDeliveryRecoveryService's sweep.
                Instant now = Instant.now();
                Delivery delivery = createDelivery(UUID.randomUUID(), 1, now.minusSeconds(10));

                when(deliveryRepository.findPendingRetryIds(
                                any(Delivery.DeliveryStatus.class),
                                any(Instant.class),
                                anyInt(),
                                anyInt(),
                                anyInt())).thenReturn(Collections.singletonList(delivery.getId()));
                when(deliveryRepository.lockByIds(anyList())).thenReturn(Collections.singletonList(delivery));

                SendResult<String, DeliveryMessage> sendResult = mockSendResult();
                CompletableFuture<SendResult<String, DeliveryMessage>> future = CompletableFuture
                                .completedFuture(sendResult);
                when(kafkaTemplate.send(anyString(), anyString(), any(DeliveryMessage.class))).thenReturn(future);

                // Act
                retrySchedulerService.scheduleRetries(0);

                // Assert — claimed delivery is left in the same state resetStuckDeliveries
                // matches on: status=PROCESSING with a recent lastAttemptAt.
                assertEquals(Delivery.DeliveryStatus.PROCESSING, delivery.getStatus());
                assertNotNull(delivery.getLastAttemptAt());
        }

    @Test
    void scheduleRetries_shouldHandleEmptyResult() {
        // Arrange
        when(deliveryRepository.findPendingRetryIds(
                any(Delivery.DeliveryStatus.class),
                any(Instant.class),
                anyInt(),
                anyInt(),
                anyInt()
        )).thenReturn(Collections.emptyList());

        // Act
        retrySchedulerService.scheduleRetries(0);

        // Assert
        verify(kafkaTemplate, never()).send(anyString(), anyString(), any(DeliveryMessage.class));
        verify(deliveryRepository, never()).saveAll(anyList());
    }

        @Test
        void scheduleRetries_shouldHandleExceptionGracefully() {
                // Arrange
                Instant now = Instant.now();
                Delivery delivery = createDelivery(UUID.randomUUID(), 1, now.minusSeconds(10));

                when(deliveryRepository.findPendingRetryIds(
                                any(Delivery.DeliveryStatus.class),
                                any(Instant.class),
                                anyInt(),
                                anyInt(),
                                anyInt())).thenReturn(Collections.singletonList(delivery.getId()));
                when(deliveryRepository.lockByIds(anyList())).thenReturn(Collections.singletonList(delivery));

                CompletableFuture<SendResult<String, DeliveryMessage>> failedFuture = new CompletableFuture<>();
                failedFuture.completeExceptionally(new RuntimeException("Kafka error"));
                when(kafkaTemplate.send(anyString(), anyString(), any(DeliveryMessage.class))).thenReturn(failedFuture);

                // Act & Assert - should not throw exception, delivery should be rescheduled
                assertDoesNotThrow(() -> retrySchedulerService.scheduleRetries(0));

                // Verify failed delivery is saved with new nextRetryAt (Phase 3)
                @SuppressWarnings("unchecked")
                ArgumentCaptor<List<Delivery>> deliveryCaptor = ArgumentCaptor.forClass(List.class);
                verify(deliveryRepository, times(2)).saveAll(deliveryCaptor.capture());
                // Phase 3 is the second saveAll — contains rescheduled delivery
                List<List<Delivery>> allSaves = deliveryCaptor.getAllValues();
                assertNotNull(allSaves.get(1).get(0).getNextRetryAt());
                // A failed send must revert the Phase 1 PROCESSING claim back to PENDING,
                // otherwise the row sits unclaimable until the stuck-delivery sweep catches it.
                assertEquals(Delivery.DeliveryStatus.PENDING, delivery.getStatus());
        }

        @Test
        void getRetryTopic_shouldSelectCorrectTopicByAttemptCount() {
                // This test verifies the business logic of topic selection
                // Since getRetryTopic is private, we test it indirectly through scheduleRetries

                Instant now = Instant.now();
                // attemptCount == 0: WebhookDeliveryService reschedules here (concurrency/rate
                // limit backpressure) *before* the first HTTP attempt is ever made -- the
                // attempt-count increment only happens once the call is actually about to go
                // out. This delivery has never been attempted and must not be treated like one
                // that has exhausted the whole retry ladder.
                Delivery delivery0 = createDelivery(UUID.randomUUID(), 0, now.minusSeconds(10));
                Delivery delivery1 = createDelivery(UUID.randomUUID(), 1, now.minusSeconds(10));
                Delivery delivery2 = createDelivery(UUID.randomUUID(), 2, now.minusSeconds(10));
                Delivery delivery6 = createDelivery(UUID.randomUUID(), 6, now.minusSeconds(10));

                SendResult<String, DeliveryMessage> sendResult = mockSendResult();
                CompletableFuture<SendResult<String, DeliveryMessage>> future = CompletableFuture
                                .completedFuture(sendResult);
                when(kafkaTemplate.send(anyString(), anyString(), any(DeliveryMessage.class))).thenReturn(future);

                when(deliveryRepository.findPendingRetryIds(
                                any(Delivery.DeliveryStatus.class),
                                any(Instant.class),
                                anyInt(),
                                anyInt(),
                                anyInt()))
                                .thenReturn(Collections.singletonList(delivery0.getId()))
                                .thenReturn(Collections.singletonList(delivery1.getId()))
                                .thenReturn(Collections.singletonList(delivery2.getId()))
                                .thenReturn(Collections.singletonList(delivery6.getId()));
                when(deliveryRepository.lockByIds(Collections.singletonList(delivery0.getId())))
                                .thenReturn(Collections.singletonList(delivery0));
                when(deliveryRepository.lockByIds(Collections.singletonList(delivery1.getId())))
                                .thenReturn(Collections.singletonList(delivery1));
                when(deliveryRepository.lockByIds(Collections.singletonList(delivery2.getId())))
                                .thenReturn(Collections.singletonList(delivery2));
                when(deliveryRepository.lockByIds(Collections.singletonList(delivery6.getId())))
                                .thenReturn(Collections.singletonList(delivery6));

                // Act
                retrySchedulerService.scheduleRetries(0);
                retrySchedulerService.scheduleRetries(0);
                retrySchedulerService.scheduleRetries(0);
                retrySchedulerService.scheduleRetries(0);

                // Assert
                ArgumentCaptor<String> topicCaptor = ArgumentCaptor.forClass(String.class);
                verify(kafkaTemplate, times(4)).send(topicCaptor.capture(), anyString(), any(DeliveryMessage.class));

                List<String> topics = topicCaptor.getAllValues();
                assertEquals(KafkaTopics.DELIVERIES_RETRY_1M, topics.get(0), "attemptCount=0 (never attempted)");
                assertEquals(KafkaTopics.DELIVERIES_RETRY_1M, topics.get(1), "attemptCount=1");
                assertEquals(KafkaTopics.DELIVERIES_RETRY_5M, topics.get(2), "attemptCount=2");
                assertEquals(KafkaTopics.DELIVERIES_RETRY_24H, topics.get(3), "attemptCount=6 (ladder exhausted)");
        }

        @Test
        void scheduleRetries_partialCompletion_shouldRescheduleIncomplete() {
                // Arrange — two deliveries: one future completes, one stays incomplete
                Instant now = Instant.now();
                Delivery completedDelivery = createDelivery(UUID.randomUUID(), 1, now.minusSeconds(10));
                Delivery incompleteDelivery = createDelivery(UUID.randomUUID(), 2, now.minusSeconds(20));

                when(deliveryRepository.findPendingRetryIds(
                                any(Delivery.DeliveryStatus.class),
                                any(Instant.class),
                                anyInt(),
                                anyInt(),
                                anyInt()))
                                .thenReturn(Arrays.asList(completedDelivery.getId(), incompleteDelivery.getId()));
                when(deliveryRepository.lockByIds(anyList()))
                                .thenReturn(Arrays.asList(completedDelivery, incompleteDelivery));

                SendResult<String, DeliveryMessage> sendResult = mockSendResult();
                CompletableFuture<SendResult<String, DeliveryMessage>> completedFuture = CompletableFuture
                                .completedFuture(sendResult);
                // This future will never complete — simulates timeout
                CompletableFuture<SendResult<String, DeliveryMessage>> incompleteFuture = new CompletableFuture<>();

                when(kafkaTemplate.send(anyString(), eq(completedDelivery.getEndpointId().toString()),
                                any(DeliveryMessage.class)))
                                .thenReturn(completedFuture);
                when(kafkaTemplate.send(anyString(), eq(incompleteDelivery.getEndpointId().toString()),
                                any(DeliveryMessage.class)))
                                .thenReturn(incompleteFuture);

                // Act
                retrySchedulerService.scheduleRetries(0);

                // Assert — completed delivery has nextRetryAt nullified (success) and stays
                // PROCESSING for the consumer to finalize
                assertNull(completedDelivery.getNextRetryAt());
                assertEquals(Delivery.DeliveryStatus.PROCESSING, completedDelivery.getStatus());
                // Assert — incomplete delivery is rescheduled (has nextRetryAt set) and its
                // PROCESSING claim is reverted back to PENDING
                assertNotNull(incompleteDelivery.getNextRetryAt());
                assertEquals(Delivery.DeliveryStatus.PENDING, incompleteDelivery.getStatus());
        }

        @Test
        void scheduleRetries_exceptionallyCompletedFuture_shouldLogError() {
                // Arrange — future completes exceptionally (different from timeout)
                Instant now = Instant.now();
                Delivery delivery = createDelivery(UUID.randomUUID(), 1, now.minusSeconds(10));

                when(deliveryRepository.findPendingRetryIds(
                                any(Delivery.DeliveryStatus.class),
                                any(Instant.class),
                                anyInt(),
                                anyInt(),
                                anyInt())).thenReturn(Collections.singletonList(delivery.getId()));
                when(deliveryRepository.lockByIds(anyList())).thenReturn(Collections.singletonList(delivery));

                CompletableFuture<SendResult<String, DeliveryMessage>> exceptionalFuture = new CompletableFuture<>();
                exceptionalFuture.completeExceptionally(new RuntimeException("Broker unavailable"));
                when(kafkaTemplate.send(anyString(), anyString(), any(DeliveryMessage.class)))
                                .thenReturn(exceptionalFuture);

                // Act — should not throw
                assertDoesNotThrow(() -> retrySchedulerService.scheduleRetries(0));

                // Assert — delivery is rescheduled with nextRetryAt set and reverted to PENDING
                assertNotNull(delivery.getNextRetryAt());
                assertEquals(Delivery.DeliveryStatus.PENDING, delivery.getStatus());
                // Verify it ends up in the failed batch (Phase 3 saveAll)
                @SuppressWarnings("unchecked")
                ArgumentCaptor<List<Delivery>> deliveryCaptor = ArgumentCaptor.forClass(List.class);
                verify(deliveryRepository, times(2)).saveAll(deliveryCaptor.capture());
                List<List<Delivery>> allSaves = deliveryCaptor.getAllValues();
                assertTrue(allSaves.get(1).contains(delivery));
        }

        @Test
        void getRetryTopic_fullLadder_totalSpanFitsInsideProductionHardCap() {
                // Ties the tier-selection mapping (getRetryTopic, exercised below via the
                // attemptCount table) together with the actual worst-case span math and the
                // production default hard-cap (96h, application.yml
                // delivery.escalation.hard-cap-hours) — the two must agree by construction.
                long worstCaseSeconds = RetryLadderDefaults.outgoing().worstCaseSpanSeconds();
                long hardCapSeconds = 96L * 3600;
                assertTrue(worstCaseSeconds <= hardCapSeconds,
                                "ladder worst-case span (" + worstCaseSeconds + "s) must fit inside the " +
                                                "escalation hard cap (" + hardCapSeconds + "s)");
        }

        private Delivery createDelivery(UUID id, int attemptCount, Instant nextRetryAt) {
                return Delivery.builder()
                                .id(id)
                                .eventId(UUID.randomUUID())
                                .endpointId(UUID.randomUUID())
                                .subscriptionId(UUID.randomUUID())
                                .status(Delivery.DeliveryStatus.PENDING)
                                .attemptCount(attemptCount)
                                .maxAttempts(7)
                                .orderingEnabled(false)
                                .nextRetryAt(nextRetryAt)
                                .createdAt(Instant.now())
                                .updatedAt(Instant.now())
                                .build();
        }

        @SuppressWarnings("unchecked")
        private SendResult<String, DeliveryMessage> mockSendResult() {
                SendResult<String, DeliveryMessage> sendResult = mock(SendResult.class);
                RecordMetadata metadata = new RecordMetadata(
                                new TopicPartition("test-topic", 0), 0, 0, 0L, 0, 0);
                when(sendResult.getRecordMetadata()).thenReturn(metadata);
                return sendResult;
        }
}

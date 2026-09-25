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
import org.springframework.transaction.support.TransactionCallback;
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
                lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
                        var callback = invocation.getArgument(0, TransactionCallback.class);
                        return callback.doInTransaction(null);
                });
                lenient().doAnswer(invocation -> {
                        @SuppressWarnings("unchecked")
                        Consumer<TransactionStatus> callback = invocation.getArgument(0, Consumer.class);
                        callback.accept(null);
                        return null;
                }).when(transactionTemplate).executeWithoutResult(any());

                lenient().when(deliveryRepository.countPending(any(Instant.class))).thenReturn(0L);

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

                retrySchedulerService.scheduleRetries(0);

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

                retrySchedulerService.scheduleRetries(0);

                verify(kafkaTemplate, times(3)).send(anyString(), anyString(), any(DeliveryMessage.class));
                // A sent row belongs to the consumer; re-saving the claim snapshot stalled the partition.
                verify(deliveryRepository, times(1)).saveAll(anyList());
        }

        @Test
        void scheduleRetries_shouldNullifyNextRetryAt() {
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

                retrySchedulerService.scheduleRetries(0);

                @SuppressWarnings("unchecked")
                ArgumentCaptor<List<Delivery>> deliveryCaptor = ArgumentCaptor.forClass(List.class);
                verify(deliveryRepository, times(1)).saveAll(deliveryCaptor.capture());
                List<List<Delivery>> allSaves = deliveryCaptor.getAllValues();
                assertNull(allSaves.get(0).get(0).getNextRetryAt());
        }

        @Test
        void scheduleRetries_claimPhase_shouldSetStatusProcessingAndLastAttemptAt() {
                // A claim left PENDING with no nextRetryAt was invisible to every sweep.
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

                retrySchedulerService.scheduleRetries(0);

                assertEquals(Delivery.DeliveryStatus.PROCESSING, delivery.getStatus());
                assertNotNull(delivery.getLastAttemptAt());
        }

        @Test
        void scheduleRetries_shouldHandleExceptionGracefully() {
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

                assertDoesNotThrow(() -> retrySchedulerService.scheduleRetries(0));

                verify(deliveryRepository, times(1)).saveAll(anyList());
                assertNotNull(delivery.getNextRetryAt());
                ArgumentCaptor<UUID> token = ArgumentCaptor.forClass(UUID.class);
                verify(deliveryRepository).handBackIfStillClaimed(
                                eq(delivery.getId()), token.capture(), eq(delivery.getNextRetryAt()));
                assertNotNull(token.getValue());
                assertEquals(Delivery.DeliveryStatus.PENDING, delivery.getStatus());
        }

        @Test
        void getRetryTopic_shouldSelectCorrectTopicByAttemptCount() {

                Instant now = Instant.now();
                // Attempt 0 is a backpressure reschedule before any HTTP call, not an exhausted ladder.
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

                retrySchedulerService.scheduleRetries(0);
                retrySchedulerService.scheduleRetries(0);
                retrySchedulerService.scheduleRetries(0);
                retrySchedulerService.scheduleRetries(0);

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
                CompletableFuture<SendResult<String, DeliveryMessage>> incompleteFuture = new CompletableFuture<>();

                when(kafkaTemplate.send(anyString(), eq(completedDelivery.getEndpointId().toString()),
                                any(DeliveryMessage.class)))
                                .thenReturn(completedFuture);
                when(kafkaTemplate.send(anyString(), eq(incompleteDelivery.getEndpointId().toString()),
                                any(DeliveryMessage.class)))
                                .thenReturn(incompleteFuture);

                retrySchedulerService.scheduleRetries(0);

                assertNull(completedDelivery.getNextRetryAt());
                assertEquals(Delivery.DeliveryStatus.PROCESSING, completedDelivery.getStatus());
                assertNotNull(incompleteDelivery.getNextRetryAt());
                assertEquals(Delivery.DeliveryStatus.PENDING, incompleteDelivery.getStatus());
        }

            @Test
        void getRetryTopic_fullLadder_totalSpanFitsInsideProductionHardCap() {
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

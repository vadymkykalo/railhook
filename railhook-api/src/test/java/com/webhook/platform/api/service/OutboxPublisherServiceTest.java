package com.webhook.platform.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.domain.entity.OutboxMessage;
import com.webhook.platform.api.domain.enums.OutboxStatus;
import com.webhook.platform.api.domain.repository.OutboxMessageRepository;
import com.webhook.platform.common.dto.DeliveryMessage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.kafka.core.KafkaTemplate;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OutboxPublisherServiceTest {

    @Mock
    private OutboxMessageRepository outboxMessageRepository;

    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private PlatformTransactionManager txManager;

    private OutboxPublisherService service;

    @BeforeEach
    void setUp() {
        // Make TransactionTemplate.execute() actually run the callback
        when(txManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));

        service = new OutboxPublisherService(
                outboxMessageRepository, kafkaTemplate, objectMapper,
                new SimpleMeterRegistry(), txManager, 100, 5, 90, 300, 1, 30);
    }

    @Test
    void shouldNotProcessWhenNoPendingMessages() {
        when(outboxMessageRepository.findPendingBatchForUpdate(anyString(), anyInt(), anyInt(), anyInt()))
                .thenReturn(Collections.emptyList());

        service.publishPendingMessages();

        verify(kafkaTemplate, never()).send(any(ProducerRecord.class));
    }

    @Test
    void shouldUseFairBatchingWithMaxPerKey() {
        when(outboxMessageRepository.findPendingBatchForUpdate(eq("PENDING"), eq(100), eq(10), eq(30)))
                .thenReturn(Collections.emptyList());

        service.publishPendingMessages();

        // Verify fair batching: 3rd arg is maxPerKey=10, 4th arg is maxPerProject=30
        verify(outboxMessageRepository).findPendingBatchForUpdate("PENDING", 100, 10, 30);
    }

    @Test
    void shouldMarkAsSendingDuringClaimPhase() throws Exception {
        OutboxMessage message = createTestMessage();
        DeliveryMessage deliveryMessage = DeliveryMessage.builder()
                .deliveryId(UUID.randomUUID())
                .build();

        when(outboxMessageRepository.findPendingBatchForUpdate(anyString(), anyInt(), anyInt(), anyInt()))
                .thenReturn(List.of(message));
        when(outboxMessageRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
        when(objectMapper.readValue(anyString(), eq(DeliveryMessage.class)))
                .thenReturn(deliveryMessage);

        @SuppressWarnings("unchecked")
        SendResult<String, Object> sendResult = mock(SendResult.class);
        CompletableFuture<SendResult<String, Object>> future = CompletableFuture.completedFuture(sendResult);
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(future);

        service.publishPendingMessages();

        // Phase 1: message was set to SENDING via saveAll during claim
        verify(outboxMessageRepository).saveAll(argThat(list -> {
            @SuppressWarnings("unchecked")
            List<OutboxMessage> msgs = (List<OutboxMessage>) list;
            return !msgs.isEmpty();
        }));

        // Phase 2: batch-marked PUBLISHED after Kafka ack
        verify(outboxMessageRepository).batchMarkPublished(anyList(), any(Instant.class));
    }

    @Test
    void shouldMarkAsFailedOnException() throws Exception {
        OutboxMessage message = createTestMessage();

        when(outboxMessageRepository.findPendingBatchForUpdate(anyString(), anyInt(), anyInt(), anyInt()))
                .thenReturn(List.of(message));
        when(outboxMessageRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
        when(objectMapper.readValue(anyString(), eq(DeliveryMessage.class)))
                .thenThrow(new RuntimeException("Parse error"));

        service.publishPendingMessages();

        // Batch-marked FAILED via bulk query
        verify(outboxMessageRepository).batchMarkFailed(anyList(), anyString(), any(Instant.class));
    }

    @Test
    void shouldMarkAsFailedOnKafkaSendFailure() throws Exception {
        OutboxMessage message = createTestMessage();
        DeliveryMessage deliveryMessage = DeliveryMessage.builder()
                .deliveryId(UUID.randomUUID())
                .build();

        when(outboxMessageRepository.findPendingBatchForUpdate(anyString(), anyInt(), anyInt(), anyInt()))
                .thenReturn(List.of(message));
        when(outboxMessageRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
        when(objectMapper.readValue(anyString(), eq(DeliveryMessage.class)))
                .thenReturn(deliveryMessage);

        // Kafka send fails definitively
        CompletableFuture<SendResult<String, Object>> future = new CompletableFuture<>();
        future.completeExceptionally(new RuntimeException("Broker unavailable"));
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(future);

        service.publishPendingMessages();

        // Batch-marked FAILED via bulk query after Kafka error
        verify(outboxMessageRepository).batchMarkFailed(anyList(), anyString(), any(Instant.class));
    }

    @Test
    void shouldNotMarkAsFailedWhenKafkaSendStillInFlight() throws Exception {
        // Regression test for P0 duplicate dispatch bug.
        // Previously, get(0ms) after batch timeout would mark in-flight sends as FAILED,
        // even though they would eventually succeed — causing duplicate dispatch on retry.
        OutboxMessage message = createTestMessage();
        DeliveryMessage deliveryMessage = DeliveryMessage.builder()
                .deliveryId(UUID.randomUUID())
                .build();

        when(outboxMessageRepository.findPendingBatchForUpdate(anyString(), anyInt(), anyInt(), anyInt()))
                .thenReturn(List.of(message));
        when(outboxMessageRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
        when(objectMapper.readValue(anyString(), eq(DeliveryMessage.class)))
                .thenReturn(deliveryMessage);

        // Kafka send never completes (simulates slow broker)
        CompletableFuture<SendResult<String, Object>> neverCompletingFuture = new CompletableFuture<>();
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(neverCompletingFuture);

        service.publishPendingMessages();

        // No batch updates should happen — messages still in-flight stay SENDING.
        // cleanupOldMessages() recovers them back to PENDING after sendingRecoverySeconds.
        verify(outboxMessageRepository, never()).batchMarkPublished(anyList(), any(Instant.class));
        verify(outboxMessageRepository, never()).batchMarkFailed(anyList(), anyString(), any(Instant.class));
        // Message remains SENDING (set during claim phase)
        assertThat(message.getStatus()).isEqualTo(OutboxStatus.SENDING);
    }

    @Test
    void findPendingBatchForUpdate_outerQuery_ordersByCreatedAt() throws Exception {
        // Regression test: the outer "SELECT * FROM outbox_messages WHERE id IN
        // (...) FOR UPDATE SKIP LOCKED" had no ORDER BY, so Postgres could return the
        // id-filtered rows in plan order even though the inner subquery computed the correct
        // rn_proj/rn_key ranking — up to maxPerKey=10 messages for one endpoint could reach
        // publishBatchAsync (and therefore Kafka) out of order. Assert the outer query (the
        // part after the inner subquery's closing paren) carries its own ORDER BY created_at.
        assertOuterQueryOrdersByCreatedAt("findPendingBatchForUpdate",
                String.class, int.class, int.class, int.class);
        assertOuterQueryOrdersByCreatedAt("findFailedMessagesForRetry",
                String.class, int.class, int.class, int.class, int.class);
    }

    private void assertOuterQueryOrdersByCreatedAt(String methodName, Class<?>... paramTypes) throws Exception {
        var method = OutboxMessageRepository.class.getMethod(methodName, paramTypes);
        org.springframework.data.jpa.repository.Query queryAnnotation =
                method.getAnnotation(org.springframework.data.jpa.repository.Query.class);
        String sql = queryAnnotation.value();

        int forUpdateIdx = sql.lastIndexOf("FOR UPDATE");
        assertThat(forUpdateIdx).as("query must use FOR UPDATE SKIP LOCKED: %s", sql).isPositive();

        int subqueryCloseIdx = sql.lastIndexOf(')', forUpdateIdx);
        String outerTail = sql.substring(subqueryCloseIdx, forUpdateIdx);

        assertThat(outerTail)
                .as("outer claim query for %s must ORDER BY created_at so the batch handed to " +
                        "publishBatchAsync is deterministic, not plan order: %s", methodName, sql)
                .containsIgnoringCase("ORDER BY created_at");
    }

    @Test
    void publishPendingMessages_sendsMessagesInRepositoryReturnOrder() throws Exception {
        // With findPendingBatchForUpdate now ordering by created_at, verify
        // publishBatchAsync itself preserves that order end-to-end instead of reshuffling it
        // (e.g. via a parallel stream or a Map keyed collection) on the way to Kafka.
        OutboxMessage m1 = createTestMessage();
        m1.setKafkaKey("key-1");
        OutboxMessage m2 = createTestMessage();
        m2.setKafkaKey("key-2");
        OutboxMessage m3 = createTestMessage();
        m3.setKafkaKey("key-3");
        List<OutboxMessage> inCreatedAtOrder = List.of(m1, m2, m3);

        when(outboxMessageRepository.findPendingBatchForUpdate(anyString(), anyInt(), anyInt(), anyInt()))
                .thenReturn(inCreatedAtOrder);
        when(outboxMessageRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
        when(objectMapper.readValue(anyString(), eq(DeliveryMessage.class)))
                .thenReturn(DeliveryMessage.builder().deliveryId(UUID.randomUUID()).build());

        @SuppressWarnings("unchecked")
        SendResult<String, Object> sendResult = mock(SendResult.class);
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(sendResult));

        service.publishPendingMessages();

        ArgumentCaptor<ProducerRecord<String, Object>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate, times(3)).send(captor.capture());
        List<Object> keysSentInOrder = captor.getAllValues().stream()
                .map(ProducerRecord::key)
                .collect(java.util.stream.Collectors.toList());
        assertThat(keysSentInOrder).containsExactly("key-1", "key-2", "key-3");
    }

    @Test
    void retryFailedMessages_recoversStuckSendingMessages_onThe30sCycle() {
        // Regression test: recoverStuckSendingMessages() used to run only inside
        // the hourly cleanupOldMessages() job, so a message stuck SENDING after a transient
        // broker hiccup could wait up to ~59 extra minutes to be reclaimed. It must now run on
        // every retryFailedMessages() poll (the 30s retry-interval-ms cycle).
        when(outboxMessageRepository.findFailedMessagesForRetry(
                anyString(), anyInt(), anyInt(), anyInt(), anyInt()))
                .thenReturn(Collections.emptyList());
        when(outboxMessageRepository.recoverStuckSendingMessages(any(Instant.class)))
                .thenReturn(0);

        service.retryFailedMessages();

        verify(outboxMessageRepository).recoverStuckSendingMessages(any(Instant.class));
    }

    @Test
    void retryFailedMessages_recoveredSendingMessages_areLoggedAndCountedAtZeroCost() {
        // A non-zero recovery result must not blow up the retry cycle (best-effort, same
        // pattern the old cleanupOldMessages() call used).
        when(outboxMessageRepository.findFailedMessagesForRetry(
                anyString(), anyInt(), anyInt(), anyInt(), anyInt()))
                .thenReturn(Collections.emptyList());
        when(outboxMessageRepository.recoverStuckSendingMessages(any(Instant.class)))
                .thenReturn(3);

        assertThatCode(() -> service.retryFailedMessages()).doesNotThrowAnyException();

        verify(outboxMessageRepository).recoverStuckSendingMessages(any(Instant.class));
    }

    @Test
    void cleanupOldMessages_noLongerRecoversStuckSendingMessages() {
        // Recovery moved to the 30s retryFailedMessages() cycle; cleanupOldMessages()
        // (hourly) must not also call it — that would just be redundant, not wrong, but this
        // pins the "moved" (not "also called") decision explicitly.
        when(outboxMessageRepository.deleteOldPublishedMessages(anyString(), any(Instant.class), anyInt()))
                .thenReturn(0);
        when(outboxMessageRepository.countByStatus(any())).thenReturn(0L);

        service.cleanupOldMessages();

        verify(outboxMessageRepository, never()).recoverStuckSendingMessages(any(Instant.class));
    }

    private OutboxMessage createTestMessage() {
        OutboxMessage message = new OutboxMessage();
        message.setId(UUID.randomUUID());
        message.setAggregateType("Delivery");
        message.setStatus(OutboxStatus.PENDING);
        message.setPayload("{\"deliveryId\":\"" + UUID.randomUUID() + "\"}");
        message.setKafkaTopic("test-topic");
        message.setKafkaKey("test-key");
        message.setRetryCount(0);
        message.setCreatedAt(Instant.now());
        return message;
    }
}

package com.webhook.platform.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.domain.entity.OutboxMessage;
import com.webhook.platform.api.domain.enums.OutboxStatus;
import com.webhook.platform.api.domain.repository.OutboxMessageRepository;
import com.webhook.platform.common.dto.DeliveryMessage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.jpa.repository.Query;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.lang.reflect.Method;
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
        when(txManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));

        service = new OutboxPublisherService(
                outboxMessageRepository, kafkaTemplate, objectMapper,
                new SimpleMeterRegistry(), txManager, 100, 5, 90, 300, 1, 30, 10);
    }

    // The per-key ceiling was a literal 10, capping one hot endpoint at ten events a second.
    @Test
    void maxPerKeyIsConfigurableLikeTheOtherTwoBoundsAreThe() {
        OutboxPublisherService tuned = new OutboxPublisherService(
                outboxMessageRepository, kafkaTemplate, objectMapper,
                new SimpleMeterRegistry(), txManager, 100, 5, 90, 300, 1, 30, 40);
        when(outboxMessageRepository.findPendingBatchForUpdate(anyString(), anyInt(), anyInt(), anyInt()))
                .thenReturn(Collections.emptyList());

        tuned.publishPendingMessages();

        verify(outboxMessageRepository).findPendingBatchForUpdate("PENDING", 100, 40, 30);
    }

    @Test
    void shouldMarkAsSendingDuringClaimPhase() throws Exception {
        stubClaim(List.of(createTestMessage()));
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(acked());

        service.publishPendingMessages();

        verify(outboxMessageRepository).saveAll(argThat(list -> !((List<?>) list).isEmpty()));
        verify(outboxMessageRepository).batchMarkPublished(anyList(), any(Instant.class));
    }

    @Test
    void shouldMarkAsFailedOnException() throws Exception {
        when(outboxMessageRepository.findPendingBatchForUpdate(anyString(), anyInt(), anyInt(), anyInt()))
                .thenReturn(List.of(createTestMessage()));
        when(outboxMessageRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
        when(objectMapper.readValue(anyString(), eq(DeliveryMessage.class)))
                .thenThrow(new RuntimeException("Parse error"));

        service.publishPendingMessages();

        verify(outboxMessageRepository).batchMarkFailed(anyList(), anyString(), any(Instant.class));
    }

    @Test
    void shouldMarkAsFailedOnKafkaSendFailure() throws Exception {
        stubClaim(List.of(createTestMessage()));
        CompletableFuture<SendResult<String, Object>> future = new CompletableFuture<>();
        future.completeExceptionally(new RuntimeException("Broker unavailable"));
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(future);

        service.publishPendingMessages();

        verify(outboxMessageRepository).batchMarkFailed(anyList(), anyString(), any(Instant.class));
    }

    // Marking an in-flight send FAILED after the batch timeout caused duplicate dispatch.
    @Test
    void shouldNotMarkAsFailedWhenKafkaSendStillInFlight() throws Exception {
        OutboxMessage message = createTestMessage();
        stubClaim(List.of(message));
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(new CompletableFuture<>());

        service.publishPendingMessages();

        verify(outboxMessageRepository, never()).batchMarkPublished(anyList(), any(Instant.class));
        verify(outboxMessageRepository, never()).batchMarkFailed(anyList(), anyString(), any(Instant.class));
        assertThat(message.getStatus()).isEqualTo(OutboxStatus.SENDING);
    }

    // Without an outer ORDER BY, Postgres returned the claimed rows in plan order.
    @Test
    void findPendingBatchForUpdate_outerQuery_ordersByCreatedAt() throws Exception {
        assertOuterQueryOrdersByCreatedAt("findPendingBatchForUpdate",
                String.class, int.class, int.class, int.class);
        assertOuterQueryOrdersByCreatedAt("findFailedMessagesForRetry",
                String.class, int.class, int.class, int.class, int.class);
    }

    private void assertOuterQueryOrdersByCreatedAt(String methodName, Class<?>... paramTypes) throws Exception {
        Method method = OutboxMessageRepository.class.getMethod(methodName, paramTypes);
        String sql = method.getAnnotation(Query.class).value();

        int forUpdateIdx = sql.lastIndexOf("FOR UPDATE");
        assertThat(forUpdateIdx).as("query must use FOR UPDATE SKIP LOCKED: %s", sql).isPositive();

        int subqueryCloseIdx = sql.lastIndexOf(')', forUpdateIdx);
        String outerTail = sql.substring(subqueryCloseIdx, forUpdateIdx);

        assertThat(outerTail)
                .as("outer claim query for %s must ORDER BY created_at: %s", methodName, sql)
                .containsIgnoringCase("ORDER BY created_at");
    }

    @Test
    void publishPendingMessages_sendsMessagesInRepositoryReturnOrder() throws Exception {
        OutboxMessage m1 = createTestMessage();
        m1.setKafkaKey("key-1");
        OutboxMessage m2 = createTestMessage();
        m2.setKafkaKey("key-2");
        OutboxMessage m3 = createTestMessage();
        m3.setKafkaKey("key-3");
        stubClaim(List.of(m1, m2, m3));
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(acked());

        service.publishPendingMessages();

        ArgumentCaptor<ProducerRecord<String, Object>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate, times(3)).send(captor.capture());
        assertThat(captor.getAllValues()).extracting(ProducerRecord::key)
                .containsExactly("key-1", "key-2", "key-3");
    }

    // Recovery used to run only in the hourly cleanup, leaving a stuck SENDING row for up to an hour.
    @Test
    void retryFailedMessages_recoversStuckSendingMessages_onThe30sCycle() {
        when(outboxMessageRepository.findFailedMessagesForRetry(
                anyString(), anyInt(), anyInt(), anyInt(), anyInt()))
                .thenReturn(Collections.emptyList());
        when(outboxMessageRepository.recoverStuckSendingMessages(any(Instant.class)))
                .thenReturn(0);

        service.retryFailedMessages();

        verify(outboxMessageRepository).recoverStuckSendingMessages(any(Instant.class));
    }

    // One capped delete an hour never caught up with an installation publishing more than that.
    @Test
    void cleanupOldMessages_keepsDeletingUntilTheBacklogIsGone() {
        when(outboxMessageRepository.deleteOldPublishedMessages(eq("PUBLISHED"), any(Instant.class), anyInt()))
                .thenReturn(5000, 5000, 5000, 120);
        when(outboxMessageRepository.deleteOldPublishedMessages(eq("DEAD"), any(Instant.class), anyInt()))
                .thenReturn(1000, 7);
        when(outboxMessageRepository.countByStatus(any())).thenReturn(0L);

        service.cleanupOldMessages();

        verify(outboxMessageRepository, times(4))
                .deleteOldPublishedMessages(eq("PUBLISHED"), any(Instant.class), eq(5000));
        verify(outboxMessageRepository, times(2))
                .deleteOldPublishedMessages(eq("DEAD"), any(Instant.class), eq(1000));
        verify(txManager, atLeast(6)).commit(any());
    }

    // A backlog of millions must not hold the ShedLock past lockAtMostFor.
    @Test
    void cleanupOldMessages_stopsAtItsBatchBudget_andLeavesTheRestForTheNextRun() {
        when(outboxMessageRepository.deleteOldPublishedMessages(anyString(), any(Instant.class), anyInt()))
                .thenAnswer(inv -> inv.getArgument(2));
        when(outboxMessageRepository.countByStatus(any())).thenReturn(0L);

        service.cleanupOldMessages();

        verify(outboxMessageRepository, times(OutboxPublisherService.CLEANUP_MAX_BATCHES))
                .deleteOldPublishedMessages(eq("PUBLISHED"), any(Instant.class), anyInt());
        verify(outboxMessageRepository, times(OutboxPublisherService.CLEANUP_MAX_BATCHES))
                .deleteOldPublishedMessages(eq("DEAD"), any(Instant.class), anyInt());
    }

    // A late ack used to be dropped, so recovery republished a message Kafka already had.
    @Test
    void aSendAcknowledgedAfterTheBatchWaitStillMarksItsRowPublished() throws Exception {
        OutboxMessage message = createTestMessage();
        stubClaim(List.of(message));
        CompletableFuture<SendResult<String, Object>> slowAck = new CompletableFuture<>();
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(slowAck);
        when(outboxMessageRepository.findFailedMessagesForRetry(
                anyString(), anyInt(), anyInt(), anyInt(), anyInt()))
                .thenReturn(Collections.emptyList());

        service.publishPendingMessages();
        verify(outboxMessageRepository, never()).batchMarkPublished(anyList(), any(Instant.class));

        @SuppressWarnings("unchecked")
        SendResult<String, Object> sendResult = mock(SendResult.class);
        slowAck.complete(sendResult);
        service.retryFailedMessages();

        verify(outboxMessageRepository).batchMarkPublished(eq(List.of(message.getId())), any(Instant.class));
    }

    // A null exception message made ConcurrentHashMap.put throw and left the row SENDING.
    @Test
    void shouldMarkAsFailedWhenTheKafkaErrorCarriesNoMessage() throws Exception {
        stubClaim(List.of(createTestMessage()));
        CompletableFuture<SendResult<String, Object>> future = new CompletableFuture<>();
        future.completeExceptionally(new NullPointerException());
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(future);

        service.publishPendingMessages();

        verify(outboxMessageRepository).batchMarkFailed(anyList(), anyString(), any(Instant.class));
    }

    @Test
    void shouldSurviveAPreparationErrorThatCarriesNoMessage() throws Exception {
        when(outboxMessageRepository.findPendingBatchForUpdate(anyString(), anyInt(), anyInt(), anyInt()))
                .thenReturn(List.of(createTestMessage()));
        when(outboxMessageRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
        when(objectMapper.readValue(anyString(), eq(DeliveryMessage.class)))
                .thenThrow(new NullPointerException());

        assertThatNoException().isThrownBy(() -> service.publishPendingMessages());

        verify(outboxMessageRepository).batchMarkFailed(anyList(), anyString(), any(Instant.class));
    }

    // Handing over the live synchronizedList risked a ConcurrentModificationException while binding.
    @Test
    void shouldNotHandTheRepositoryAListStillBeingWrittenTo() throws Exception {
        stubClaim(List.of(createTestMessage(), createTestMessage()));
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(acked());

        service.publishPendingMessages();

        ArgumentCaptor<List<UUID>> published = ArgumentCaptor.forClass(List.class);
        verify(outboxMessageRepository).batchMarkPublished(published.capture(), any(Instant.class));
        assertThat(published.getValue().getClass().getName())
                .as("the repository must be handed a snapshot, not the live collector")
                .doesNotContain("Synchronized");
        assertThat(published.getValue()).hasSize(2);
    }

    private void stubClaim(List<OutboxMessage> messages) throws Exception {
        when(outboxMessageRepository.findPendingBatchForUpdate(anyString(), anyInt(), anyInt(), anyInt()))
                .thenReturn(messages);
        when(outboxMessageRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
        when(objectMapper.readValue(anyString(), eq(DeliveryMessage.class)))
                .thenReturn(DeliveryMessage.builder().deliveryId(UUID.randomUUID()).build());
    }

    private static CompletableFuture<SendResult<String, Object>> acked() {
        @SuppressWarnings("unchecked")
        SendResult<String, Object> sendResult = mock(SendResult.class);
        return CompletableFuture.completedFuture(sendResult);
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

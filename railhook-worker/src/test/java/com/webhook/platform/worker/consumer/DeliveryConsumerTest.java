package com.webhook.platform.worker.consumer;

import com.webhook.platform.common.dto.DeliveryMessage;
import com.webhook.platform.worker.attempt.AttemptRunner;
import com.webhook.platform.worker.attempt.DeliveryAttemptMetrics;
import com.webhook.platform.worker.attempt.OutgoingAttemptStoreFactory;
import com.webhook.platform.worker.domain.repository.DeliveryRepository;
import com.webhook.platform.worker.service.BoundedAsyncExecutor;
import com.webhook.platform.worker.service.WebhookDeliveryService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DeliveryConsumerTest {

    private WebhookDeliveryService webhookDeliveryService;
    private BoundedAsyncExecutor asyncExecutor;
    private DeliveryConsumer consumer;

    @BeforeEach
    void setUp() {
        webhookDeliveryService = mock(WebhookDeliveryService.class);
        // Real executor, not mocked: the bug is about which thread the shutdown
        // rejection is visible on, so a mock would hide it.
        asyncExecutor = new BoundedAsyncExecutor("test-delivery", 4, 5, new SimpleMeterRegistry());
        consumer = new DeliveryConsumer(webhookDeliveryService, asyncExecutor, mock(KafkaListenerEndpointRegistry.class));
    }

    @AfterEach
    void tearDown() {
        asyncExecutor.shutdown();
    }

    @Test
    void consumeDispatch_shouldSubmitNormally_whenNotShuttingDown() throws Exception {
        DeliveryMessage message = dispatchMessage();
        Acknowledgment ack = mock(Acknowledgment.class);

        assertDoesNotThrow(() -> consumer.consumeDispatch(message, "key", "deliveries.dispatch", null, ack));

        Thread.sleep(200);
        verify(webhookDeliveryService).processDelivery(message, false);
        verify(ack).acknowledge();
    }

    /**
     * A record the listener still receives while the worker stops is an ordinary record. The
     * shutdown flag used to turn it into a not-retryable exception, which the error handler sends
     * straight to the dead-letter topic — a topic nothing consumes — while the Delivery waited an
     * hour for the stranded-PENDING sweep.
     */
    @Test
    void aRecordArrivingWhileTheWorkerStops_isDelivered_notSentToTheDeadLetterTopic() {
        AttemptRunner runner = mock(AttemptRunner.class);
        WebhookDeliveryService stopping = new WebhookDeliveryService(
                runner, mock(OutgoingAttemptStoreFactory.class), mock(DeliveryAttemptMetrics.class),
                mock(DeliveryRepository.class), mock(TransactionTemplate.class));
        stopping.onShutdown();
        DeliveryConsumer consumerOfAStoppingWorker =
                new DeliveryConsumer(stopping, asyncExecutor, mock(KafkaListenerEndpointRegistry.class));

        DeliveryMessage dispatch = dispatchMessage();
        Acknowledgment dispatchAck = mock(Acknowledgment.class);
        assertDoesNotThrow(() -> consumerOfAStoppingWorker.consumeDispatch(
                dispatch, "key", "deliveries.dispatch", null, dispatchAck));

        DeliveryMessage retry = dispatchMessage();
        Acknowledgment retryAck = mock(Acknowledgment.class);
        assertDoesNotThrow(() -> consumerOfAStoppingWorker.consumeRetry(
                retry, "key", "deliveries.retry.1m", null, retryAck));

        verify(dispatchAck, timeout(5000)).acknowledge();
        verify(retryAck, timeout(5000)).acknowledge();
        verify(runner, timeout(5000).times(2)).run(any(), any());
    }

    @Test
    void consumeDispatch_shouldRescheduleAndAck_whenExecutorFull() throws Exception {
        // Fill the pool (size 4) so the next submission is rejected — a
        // rejected record must be explicitly rescheduled and acked, not left unacked.
        fillExecutorPool();

        DeliveryMessage message = dispatchMessage();
        Acknowledgment ack = mock(Acknowledgment.class);

        consumer.consumeDispatch(message, "key", "deliveries.dispatch", null, ack);

        verify(webhookDeliveryService).rescheduleForBackpressure(message, false);
        verify(ack).acknowledge();
        verify(webhookDeliveryService, never()).processDelivery(any(), anyBoolean());
    }

    @Test
    void consumeRetry_shouldRescheduleAndAck_whenExecutorFull() throws Exception {
        fillExecutorPool();

        DeliveryMessage message = dispatchMessage();
        Acknowledgment ack = mock(Acknowledgment.class);

        consumer.consumeRetry(message, "key", "deliveries.retry.1m", null, ack);

        verify(webhookDeliveryService).rescheduleForBackpressure(message, true);
        verify(ack).acknowledge();
        verify(webhookDeliveryService, never()).processDelivery(any(), anyBoolean());
    }

    private void fillExecutorPool() throws InterruptedException {
        CountDownLatch blockLatch = new CountDownLatch(1);
        CountDownLatch allStarted = new CountDownLatch(4);
        for (int i = 0; i < 4; i++) {
            asyncExecutor.trySubmit(() -> {
                allStarted.countDown();
                try {
                    blockLatch.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, mock(Acknowledgment.class), "fill-" + i);
        }
        assertTrue(allStarted.await(5, TimeUnit.SECONDS));
    }

    private DeliveryMessage dispatchMessage() {
        DeliveryMessage message = new DeliveryMessage();
        message.setDeliveryId(UUID.randomUUID());
        message.setEndpointId(UUID.randomUUID());
        message.setAttemptCount(0);
        return message;
    }
}

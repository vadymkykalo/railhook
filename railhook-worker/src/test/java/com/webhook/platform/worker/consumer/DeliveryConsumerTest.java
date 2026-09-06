package com.webhook.platform.worker.consumer;

import com.webhook.platform.common.dto.DeliveryMessage;
import com.webhook.platform.worker.service.BoundedAsyncExecutor;
import com.webhook.platform.worker.service.ShutdownRejectedException;
import com.webhook.platform.worker.service.WebhookDeliveryService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.support.Acknowledgment;

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
    void consumeDispatch_shouldThrowBeforeAcking_whenShuttingDown() {
        when(webhookDeliveryService.isShuttingDown()).thenReturn(true);
        DeliveryMessage message = dispatchMessage();
        Acknowledgment ack = mock(Acknowledgment.class);

        assertThrows(ShutdownRejectedException.class,
                () -> consumer.consumeDispatch(message, "key", "deliveries.dispatch", null, ack));

        verify(ack, never()).acknowledge();
        verify(webhookDeliveryService, never()).processDelivery(any(), anyBoolean());
    }

    @Test
    void consumeDispatch_shouldSubmitNormally_whenNotShuttingDown() throws Exception {
        when(webhookDeliveryService.isShuttingDown()).thenReturn(false);
        DeliveryMessage message = dispatchMessage();
        Acknowledgment ack = mock(Acknowledgment.class);

        assertDoesNotThrow(() -> consumer.consumeDispatch(message, "key", "deliveries.dispatch", null, ack));

        Thread.sleep(200);
        verify(webhookDeliveryService).processDelivery(message, false);
        verify(ack).acknowledge();
    }

    @Test
    void consumeRetry_shouldThrowBeforeAcking_whenShuttingDown() {
        when(webhookDeliveryService.isShuttingDown()).thenReturn(true);
        DeliveryMessage message = dispatchMessage();
        Acknowledgment ack = mock(Acknowledgment.class);

        assertThrows(ShutdownRejectedException.class,
                () -> consumer.consumeRetry(message, "key", "deliveries.retry.1m", null, ack));

        verify(ack, never()).acknowledge();
        verify(webhookDeliveryService, never()).processDelivery(any(), anyBoolean());
    }

    @Test
    void consumeDispatch_shouldRescheduleAndAck_whenExecutorFull() throws Exception {
        // Fill the pool (size 4) so the next submission is rejected — a
        // rejected record must be explicitly rescheduled and acked, not left unacked.
        fillExecutorPool();

        when(webhookDeliveryService.isShuttingDown()).thenReturn(false);
        DeliveryMessage message = dispatchMessage();
        Acknowledgment ack = mock(Acknowledgment.class);

        consumer.consumeDispatch(message, "key", "deliveries.dispatch", null, ack);

        verify(webhookDeliveryService).rescheduleForBackpressure(message.getDeliveryId(), false);
        verify(ack).acknowledge();
        verify(webhookDeliveryService, never()).processDelivery(any(), anyBoolean());
    }

    @Test
    void consumeRetry_shouldRescheduleAndAck_whenExecutorFull() throws Exception {
        fillExecutorPool();

        when(webhookDeliveryService.isShuttingDown()).thenReturn(false);
        DeliveryMessage message = dispatchMessage();
        Acknowledgment ack = mock(Acknowledgment.class);

        consumer.consumeRetry(message, "key", "deliveries.retry.1m", null, ack);

        verify(webhookDeliveryService).rescheduleForBackpressure(message.getDeliveryId(), true);
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

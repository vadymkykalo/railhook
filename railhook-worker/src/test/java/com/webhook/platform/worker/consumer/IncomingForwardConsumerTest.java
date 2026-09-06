package com.webhook.platform.worker.consumer;

import com.webhook.platform.common.constants.KafkaTopics;
import com.webhook.platform.common.dto.IncomingForwardMessage;
import com.webhook.platform.worker.service.BoundedAsyncExecutor;
import com.webhook.platform.worker.service.IncomingForwardService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.support.Acknowledgment;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for IncomingForwardConsumer -- the incoming-forward analogue of
 * DeliveryConsumerTest. Like the outgoing consumer, this one hands the attempt back to the
 * retry ladder and acks when the executor is full.
 *
 * <p>An earlier revision deliberately did the opposite and left the record unacked, on the
 * belief that the container's own backpressure would cause redelivery. It does not: the
 * listener factory sets asyncAcks(true), under which an unacked record is not redelivered
 * until a rebalance or restart and blocks this partition's offset commits in the meantime --
 * the failure mode DeliveryConsumer documents as fatal.
 */
class IncomingForwardConsumerTest {

    private IncomingForwardService forwardService;
    private BoundedAsyncExecutor asyncExecutor;
    private IncomingForwardConsumer consumer;

    @BeforeEach
    void setUp() {
        forwardService = mock(IncomingForwardService.class);
        asyncExecutor = new BoundedAsyncExecutor("test-incoming-forward", 4, 5, new SimpleMeterRegistry());
        consumer = new IncomingForwardConsumer(forwardService, asyncExecutor, mock(KafkaListenerEndpointRegistry.class));
    }

    @AfterEach
    void tearDown() {
        asyncExecutor.shutdown();
    }

    @Test
    void consume_submitsToForwardServiceAndAcks() throws Exception {
        IncomingForwardMessage message = forwardMessage();
        ConsumerRecord<String, IncomingForwardMessage> record = consumerRecord(message);
        Acknowledgment ack = mock(Acknowledgment.class);

        consumer.consume(record, ack);

        Thread.sleep(200);
        verify(forwardService).processForward(message);
        verify(ack).acknowledge();
    }

    @Test
    void consume_executorFull_reschedulesViaRetryLadderAndAcks() throws Exception {
        fillExecutorPool();

        IncomingForwardMessage message = forwardMessage();
        ConsumerRecord<String, IncomingForwardMessage> record = consumerRecord(message);
        Acknowledgment ack = mock(Acknowledgment.class);

        consumer.consume(record, ack);

        // Not processed now -- but handed to the retry ladder and acked, so the partition
        // keeps committing instead of stalling until the next rebalance.
        verify(forwardService, never()).processForward(any());
        verify(forwardService).rescheduleForBackpressure(message);
        verify(ack).acknowledge();
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

    private IncomingForwardMessage forwardMessage() {
        return IncomingForwardMessage.builder()
                .incomingEventId(UUID.randomUUID())
                .destinationId(UUID.randomUUID())
                .attemptCount(0)
                .replay(false)
                .build();
    }

    @SuppressWarnings("unchecked")
    private ConsumerRecord<String, IncomingForwardMessage> consumerRecord(IncomingForwardMessage message) {
        ConsumerRecord<String, IncomingForwardMessage> record = mock(ConsumerRecord.class);
        when(record.value()).thenReturn(message);
        when(record.topic()).thenReturn(KafkaTopics.INCOMING_FORWARD_DISPATCH);
        org.apache.kafka.common.header.Headers headers = new org.apache.kafka.common.header.internals.RecordHeaders();
        when(record.headers()).thenReturn(headers);
        return record;
    }
}

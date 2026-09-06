package com.webhook.platform.worker.config;

import com.webhook.platform.common.dto.DeliveryMessage;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.AcknowledgingMessageListener;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;

/**
 * Reproduces the out-of-order-ack defect against a real broker: records 5..9 are handed to the
 * listener, complete (and ack) before records 0..4 do, mirroring how BoundedAsyncExecutor
 * acks from whichever pool thread finishes first. Without {@code asyncAcks}, MANUAL ack
 * mode commits straight to the highest acked offset regardless of order, so the commit
 * jumps to 10 while 0..4 are still in flight — a kill at that point loses them. With
 * {@code asyncAcks}, the commit is deferred until every lower offset in the batch has
 * also been acked.
 *
 * <p>Builds the container from the real {@link KafkaConsumerConfig} bean method (not a
 * hand-rolled copy) so this test regresses if the {@code setAsyncAcks(true)} fix in that
 * class is ever reverted.</p>
 */
@Testcontainers
class KafkaAckOrderingIntegrationTest {

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.7.0");

    private static final String TOPIC = "ack-ordering-test";
    private static final String GROUP = "ack-ordering-test-group";
    private static final int RECORD_COUNT = 10;

    private ExecutorService completionPool;
    private ConcurrentMessageListenerContainer<String, DeliveryMessage> container;

    @BeforeEach
    void setUp() throws Exception {
        completionPool = Executors.newFixedThreadPool(RECORD_COUNT);
        try (AdminClient admin = AdminClient.create(
                Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(TOPIC, 1, (short) 1))).all().get(30, TimeUnit.SECONDS);
        }
    }

    @AfterEach
    void tearDown() {
        if (container != null && container.isRunning()) {
            container.stop();
        }
        completionPool.shutdownNow();
    }

    @Test
    void committedOffset_neverRunsAheadOfIncompleteWork_whenAckedOutOfOrder() throws Exception {
        // KafkaConsumerConfig is instantiated directly (not via Spring context) so this
        // test exercises the exact container factory the worker registers in production.
        KafkaConsumerConfig config = new KafkaConsumerConfig(mock(KafkaOperations.class));
        ReflectionTestUtils.setField(config, "bootstrapServers", KAFKA.getBootstrapServers());
        ReflectionTestUtils.setField(config, "groupId", GROUP);
        ReflectionTestUtils.setField(config, "incomingGroupId", "unused-incoming-group");
        ReflectionTestUtils.setField(config, "maxRetries", 1);
        ReflectionTestUtils.setField(config, "retryIntervalMs", 1000L);
        ReflectionTestUtils.setField(config, "deliveryConcurrency", 1);
        ReflectionTestUtils.setField(config, "incomingConcurrency", 1);
        ReflectionTestUtils.setField(config, "autoOffsetReset", "earliest");

        var factory = config.kafkaListenerContainerFactory();
        container = factory.createContainer(TOPIC);
        // Default poll timeout is 5s: acks queued from a non-consumer thread are only
        // drained/committed at the top of the next poll loop iteration (doProcessCommits,
        // before doPoll). Shorten it so the test doesn't have to wait a full 5s per check.
        container.getContainerProperties().setPollTimeout(200L);

        // Gate per record offset: the test controls exactly when each record's async
        // "work" finishes and calls acknowledge(), just like BoundedAsyncExecutor does
        // from a pool thread once the HTTP delivery attempt completes.
        Map<Long, CompletableFuture<Void>> gates = new java.util.concurrent.ConcurrentHashMap<>();
        for (long i = 0; i < RECORD_COUNT; i++) {
            gates.put(i, new CompletableFuture<>());
        }
        CountDownLatch received = new CountDownLatch(RECORD_COUNT);

        container.getContainerProperties().setMessageListener(
                (AcknowledgingMessageListener<String, DeliveryMessage>) (record, acknowledgment) -> {
                    long offset = record.offset();
                    received.countDown();
                    completionPool.submit(() -> {
                        try {
                            gates.get(offset).get(20, TimeUnit.SECONDS);
                            acknowledgment.acknowledge();
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    });
                });

        produceRecords(RECORD_COUNT);

        container.start();
        assertTrue(received.await(30, TimeUnit.SECONDS), "all records should reach the listener");

        // Records 5..9 finish (and ack) first; 0..4 are still "in flight".
        for (long offset = 5; offset < RECORD_COUNT; offset++) {
            gates.get(offset).complete(null);
        }

        // Give the out-of-order acks time to reach the container and (if it were going
        // to) commit — several poll cycles at the shortened 200ms timeout. This is the
        // moment a SIGKILL would previously have lost 0..4.
        Thread.sleep(1500);

        Long committedBeforeCompletion = fetchCommittedOffset();
        assertTrue(committedBeforeCompletion == null || committedBeforeCompletion <= 0,
                "committed offset must not run ahead of incomplete record 0, but was: " + committedBeforeCompletion);

        // Now let 0..4 finish in order, unblocking the deferred commit.
        for (long offset = 0; offset < 5; offset++) {
            gates.get(offset).complete(null);
        }

        Long committedAfterCompletion = pollUntilCommittedAtLeast(RECORD_COUNT, Duration.ofSeconds(20));
        assertEquals(RECORD_COUNT, (long) committedAfterCompletion,
                "once every record is acked, the committed offset should reach the end of the batch");
    }

    private void produceRecords(int count) throws Exception {
        Map<String, Object> producerProps = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        KafkaTemplate<String, DeliveryMessage> producer =
                new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(producerProps));
        try {
            for (int i = 0; i < count; i++) {
                DeliveryMessage message = DeliveryMessage.builder()
                        .deliveryId(UUID.randomUUID())
                        .eventId(UUID.randomUUID())
                        .endpointId(UUID.randomUUID())
                        .attemptCount(0)
                        .build();
                producer.send(TOPIC, "key-" + i, message).get(10, TimeUnit.SECONDS);
            }
        } finally {
            producer.destroy();
        }
    }

    private Long fetchCommittedOffset() throws Exception {
        try (AdminClient admin = AdminClient.create(
                Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            Map<TopicPartition, OffsetAndMetadata> offsets = admin.listConsumerGroupOffsets(GROUP)
                    .partitionsToOffsetAndMetadata()
                    .get(10, TimeUnit.SECONDS);
            OffsetAndMetadata offsetAndMetadata = offsets.get(new TopicPartition(TOPIC, 0));
            return offsetAndMetadata == null ? null : offsetAndMetadata.offset();
        }
    }

    private Long pollUntilCommittedAtLeast(long target, Duration timeout) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        Long last = null;
        while (Instant.now().isBefore(deadline)) {
            last = fetchCommittedOffset();
            if (last != null && last >= target) {
                return last;
            }
            Thread.sleep(200);
        }
        fail("committed offset never reached " + target + ", last seen: " + last);
        return last;
    }
}

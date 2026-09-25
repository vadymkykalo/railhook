package com.webhook.platform.worker.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.common.constants.KafkaTopics;
import com.webhook.platform.common.dto.DeliveryMessage;
import com.webhook.platform.common.dto.IncomingForwardMessage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.listener.AcknowledgingMessageListener;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

// Without ErrorHandlingDeserializer the failure is inside poll(), so the DLQ recoverer never sees it.
@Testcontainers
class KafkaPoisonRecordIntegrationTest {

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.7.0");

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final byte[] POISON = "{not json".getBytes(StandardCharsets.UTF_8);

    private ConcurrentMessageListenerContainer<String, ?> container;

    @BeforeAll
    static void createDeadLetterTopics() throws Exception {
        try (AdminClient admin = AdminClient.create(
                Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(
                    new NewTopic(KafkaTopics.DELIVERIES_DLQ, 1, (short) 1),
                    new NewTopic(KafkaTopics.INCOMING_FORWARD_DLQ, 1, (short) 1)))
                    .all().get(30, TimeUnit.SECONDS);
        }
    }

    @AfterEach
    void stopContainer() {
        if (container != null && container.isRunning()) {
            container.stop();
        }
    }

    @Test
    void aPoisonDeliveryIsDeadLetteredAndTheDeliveryBehindItIsStillConsumed() throws Exception {
        String topic = "poison-deliveries-" + UUID.randomUUID();
        DeliveryMessage good = DeliveryMessage.builder()
                .deliveryId(UUID.randomUUID()).eventId(UUID.randomUUID()).endpointId(UUID.randomUUID())
                .attemptCount(0).build();

        List<Object> consumed = runPoisonThenGood(config().kafkaListenerContainerFactory(), topic, POISON, good);

        assertThat(consumed).containsExactly(good);
        assertDeadLettered(KafkaTopics.DELIVERIES_DLQ, topic, value -> assertThat(value)
                .as("the bytes that could not be read, not a re-encoding of them")
                .isEqualTo(POISON));
    }

    @Test
    void aDeliveryTheListenerFailsOnIsDeadLetteredAndTheDeliveryBehindItIsStillConsumed() throws Exception {
        String topic = "failing-deliveries-" + UUID.randomUUID();
        DeliveryMessage failing = DeliveryMessage.builder()
                .deliveryId(UUID.randomUUID()).eventId(UUID.randomUUID()).endpointId(UUID.randomUUID())
                .attemptCount(0).build();
        DeliveryMessage good = DeliveryMessage.builder()
                .deliveryId(UUID.randomUUID()).eventId(UUID.randomUUID()).endpointId(UUID.randomUUID())
                .attemptCount(0).build();

        List<Object> consumed = runPoisonThenGood(config().kafkaListenerContainerFactory(), topic,
                OBJECT_MAPPER.writeValueAsBytes(failing), good);

        assertThat(consumed).containsExactly(good);
        assertDeadLettered(KafkaTopics.DELIVERIES_DLQ, topic, value -> assertThat(readDelivery(value))
                .as("the message the listener failed on, as JSON")
                .isEqualTo(failing));
    }

    @Test
    void aPoisonForwardIsDeadLetteredAndTheForwardBehindItIsStillConsumed() throws Exception {
        String topic = "poison-forwards-" + UUID.randomUUID();
        IncomingForwardMessage good = IncomingForwardMessage.builder()
                .incomingEventId(UUID.randomUUID()).destinationId(UUID.randomUUID())
                .incomingSourceId(UUID.randomUUID()).attemptCount(0).build();

        List<Object> consumed = runPoisonThenGood(
                config().incomingForwardListenerContainerFactory(), topic, POISON, good);

        assertThat(consumed).containsExactly(good);
        assertDeadLettered(KafkaTopics.INCOMING_FORWARD_DLQ, topic, value -> assertThat(value)
                .as("the bytes that could not be read, not a re-encoding of them")
                .isEqualTo(POISON));
    }

    private KafkaConsumerConfig config() {
        KafkaProducerConfig producerConfig = new KafkaProducerConfig(new SimpleMeterRegistry());
        ReflectionTestUtils.setField(producerConfig, "bootstrapServers", KAFKA.getBootstrapServers());
        ReflectionTestUtils.setField(producerConfig, "deliveryTimeoutMs", 120000);
        ReflectionTestUtils.setField(producerConfig, "maxBlockMs", 10000);

        KafkaConsumerConfig config = new KafkaConsumerConfig(
                producerConfig.deadLetterKafkaTemplate(), new SimpleMeterRegistry());
        ReflectionTestUtils.setField(config, "bootstrapServers", KAFKA.getBootstrapServers());
        ReflectionTestUtils.setField(config, "groupId", "poison-deliveries-" + UUID.randomUUID());
        ReflectionTestUtils.setField(config, "incomingGroupId", "poison-forwards-" + UUID.randomUUID());
        ReflectionTestUtils.setField(config, "maxRetries", 1);
        ReflectionTestUtils.setField(config, "retryIntervalMs", 100L);
        ReflectionTestUtils.setField(config, "deliveryConcurrency", 1);
        ReflectionTestUtils.setField(config, "incomingConcurrency", 1);
        ReflectionTestUtils.setField(config, "autoOffsetReset", "earliest");
        return config;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    // The listener refuses any record keyed "poison" that did deserialize, as a listener failure.
    private <V> List<Object> runPoisonThenGood(ConcurrentKafkaListenerContainerFactory<String, V> factory,
            String topic, byte[] first, Object good) throws Exception {
        try (AdminClient admin = AdminClient.create(
                Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1))).all().get(30, TimeUnit.SECONDS);
        }
        try (KafkaProducer<String, byte[]> raw = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class));
             KafkaProducer<String, Object> json = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class))) {
            raw.send(new ProducerRecord<>(topic, "poison", first)).get(10, TimeUnit.SECONDS);
            json.send(new ProducerRecord<>(topic, "good", good)).get(10, TimeUnit.SECONDS);
        }

        BlockingQueue<Object> received = new LinkedBlockingQueue<>();
        ConcurrentMessageListenerContainer<String, V> created = factory.createContainer(topic);
        created.getContainerProperties().setPollTimeout(200L);
        created.getContainerProperties().setMessageListener(
                (AcknowledgingMessageListener<String, V>) (record, ack) -> {
                    if ("poison".equals(record.key())) {
                        throw new IllegalStateException("listener refused " + record.value());
                    }
                    received.add(record.value());
                    ack.acknowledge();
                });
        container = created;
        created.start();

        Object arrived = received.poll(30, TimeUnit.SECONDS);
        List<Object> consumed = new ArrayList<>();
        if (arrived != null) {
            consumed.add(arrived);
            Object more = received.poll(1, TimeUnit.SECONDS);
            if (more != null) {
                consumed.add(more);
            }
        }
        return consumed;
    }

    private void assertDeadLettered(String dlqTopic, String sourceTopic, Consumer<byte[]> valueAssertion) {
        try (KafkaConsumer<String, byte[]> dlq = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "dlq-reader-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class))) {
            dlq.subscribe(List.of(dlqTopic));
            Instant deadline = Instant.now().plusSeconds(30);
            while (Instant.now().isBefore(deadline)) {
                ConsumerRecords<String, byte[]> records = dlq.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, byte[]> record : records) {
                    String originalTopic = header(record, "kafka_dlt-original-topic");
                    if (sourceTopic.equals(originalTopic)) {
                        assertThat(record.key()).isEqualTo("poison");
                        valueAssertion.accept(record.value());
                        return;
                    }
                }
            }
        }
        throw new AssertionError("no dead letter from " + sourceTopic + " reached " + dlqTopic);
    }

    private static DeliveryMessage readDelivery(byte[] value) {
        try {
            return OBJECT_MAPPER.readValue(value, DeliveryMessage.class);
        } catch (java.io.IOException e) {
            throw new AssertionError("dead letter is not a DeliveryMessage: " + new String(value, StandardCharsets.UTF_8), e);
        }
    }

    private static String header(ConsumerRecord<?, ?> record, String name) {
        var header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}

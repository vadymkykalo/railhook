package com.webhook.platform.worker.config;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.producer.Producer;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

// Client metrics exist only with a Micrometer listener on the factory.
class KafkaClientMetricsTest {

    @Test
    @SuppressWarnings("unchecked")
    void consumersFromBothFactoriesPublishTheirClientMetrics() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        KafkaConsumerConfig config = new KafkaConsumerConfig(mock(KafkaOperations.class), registry);
        ReflectionTestUtils.setField(config, "bootstrapServers", "localhost:9");
        ReflectionTestUtils.setField(config, "groupId", "delivery-test");
        ReflectionTestUtils.setField(config, "incomingGroupId", "incoming-test");
        ReflectionTestUtils.setField(config, "autoOffsetReset", "earliest");

        try (Consumer<?, ?> delivery = config.consumerFactory().createConsumer();
             Consumer<?, ?> incoming = config.incomingForwardConsumerFactory().createConsumer()) {
            assertThat(registry.getMeters())
                    .extracting(meter -> meter.getId().getName())
                    .anyMatch(name -> name.startsWith("kafka.consumer."));
            assertThat(registry.getMeters())
                    .extracting(meter -> meter.getId().getTag("client.id"))
                    .as("both consumer groups are measured")
                    .contains(clientId(delivery), clientId(incoming));
        }
    }

    @Test
    void producersFromEveryFactoryPublishTheirClientMetrics() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        KafkaProducerConfig config = new KafkaProducerConfig(registry);
        ReflectionTestUtils.setField(config, "bootstrapServers", "localhost:9");
        ReflectionTestUtils.setField(config, "deliveryTimeoutMs", 120000);
        ReflectionTestUtils.setField(config, "maxBlockMs", 1000);

        try (Producer<?, ?> delivery = config.producerFactory().createProducer();
             Producer<?, ?> incoming = config.incomingForwardProducerFactory().createProducer()) {
            assertThat(registry.getMeters())
                    .extracting(meter -> meter.getId().getName())
                    .anyMatch(name -> name.startsWith("kafka.producer."));
            assertThat(registry.getMeters())
                    .extracting(meter -> meter.getId().getTag("client.id"))
                    .as("every producer is measured")
                    .contains(clientId(delivery), clientId(incoming));
        }
    }

    private static String clientId(Consumer<?, ?> consumer) {
        return consumer.metrics().keySet().iterator().next().tags().get("client-id");
    }

    private static String clientId(Producer<?, ?> producer) {
        return producer.metrics().keySet().iterator().next().tags().get("client-id");
    }
}

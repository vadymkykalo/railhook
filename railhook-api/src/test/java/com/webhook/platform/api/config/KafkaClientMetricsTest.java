package com.webhook.platform.api.config;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.producer.Producer;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The outbox producer's own Kafka client metrics — record send rate, queue time, batch size,
 * connections — reach the registry Prometheus scrapes.
 *
 * <p>Spring only times the KafkaTemplate call; the client's metrics exist only when a Micrometer
 * listener is on the factory. Without one every panel of the Kafka dashboard was empty.
 */
class KafkaClientMetricsTest {

    @Test
    void aProducerFromTheFactoryPublishesItsClientMetrics() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        KafkaProducerConfig config = new KafkaProducerConfig(registry);
        ReflectionTestUtils.setField(config, "bootstrapServers", "localhost:9");
        ReflectionTestUtils.setField(config, "deliveryTimeoutMs", 120000);
        ReflectionTestUtils.setField(config, "maxBlockMs", 1000);

        try (Producer<String, Object> producer = config.producerFactory().createProducer()) {
            assertThat(registry.getMeters())
                    .extracting(meter -> meter.getId().getName())
                    .anyMatch(name -> name.startsWith("kafka.producer."));
        }
    }
}

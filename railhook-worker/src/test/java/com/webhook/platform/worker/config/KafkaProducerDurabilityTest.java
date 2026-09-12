package com.webhook.platform.worker.config;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import com.webhook.platform.common.dto.DeliveryMessage;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The producer settings the outbox depends on, asserted rather than assumed.
 *
 * <p>These live in Java and not in {@code application.yml}: the config class builds its own
 * property map, so the {@code spring.kafka.producer.*} keys in the yaml are inert. Anyone tuning
 * them there is tuning nothing, which is most of why this test exists.
 *
 * <p>The api declares the same settings in its own copy of this class. They are not shared;
 * {@code KafkaProducerDurabilityTest} exists on both sides so the two cannot drift silently.
 */
class KafkaProducerDurabilityTest {

    @SuppressWarnings("unchecked")
    private Map<String, Object> producerProperties() {
        KafkaProducerConfig config = new KafkaProducerConfig();
        ReflectionTestUtils.setField(config, "bootstrapServers", "localhost:9092");
        DefaultKafkaProducerFactory<String, DeliveryMessage> factory =
                (DefaultKafkaProducerFactory<String, DeliveryMessage>) config.producerFactory();
        return (Map<String, Object>) factory.getConfigurationProperties();
    }

    @Test
    @DisplayName("idempotence is on and acks are all — the outbox marks a row PUBLISHED on this")
    void writesAreDurableBeforeTheyAreAcknowledged() {
        Map<String, Object> props = producerProperties();

        assertThat(props.get(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG)).isEqualTo(true);
        assertThat(props.get(ProducerConfig.ACKS_CONFIG)).isEqualTo("all");
    }

    @Test
    @DisplayName("retries are not capped below what a leader election takes")
    void retriesAreBoundedByTimeRatherThanByCount() {
        // retries=3 with the default retry.backoff.ms=100 is roughly 300ms of patience. An
        // ordinary leader election or a rolling broker restart outlasts that, so sends failed
        // that the default configuration would have ridden out - and each one costs its outbox
        // row a full retry cycle. An idempotent producer defaults to Integer.MAX_VALUE precisely
        // so that delivery.timeout.ms is the thing that decides, and that is the knob an
        // operator can reason about.
        Map<String, Object> props = producerProperties();

        assertThat(props.get(ProducerConfig.RETRIES_CONFIG))
                .as("leave retries to the idempotent producer's default, bounded by delivery.timeout.ms")
                .isNull();
        assertThat(props.get(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG))
                .as("declared, so the bound is visible rather than inherited")
                .isNotNull();
    }

    @Test
    @DisplayName("a send cannot block the scheduled poll thread indefinitely")
    void metadataWaitIsBounded() {
        // OutboxPublisherService calls kafkaTemplate.send() synchronously on the @Scheduled
        // thread, and max.block.ms defaults to 60s. The API shares eight scheduler threads
        // across 34 scheduled methods, so an unreachable broker takes them out one per poll.
        Map<String, Object> props = producerProperties();

        assertThat((Integer) props.get(ProducerConfig.MAX_BLOCK_MS_CONFIG)).isLessThanOrEqualTo(10_000);
    }
}

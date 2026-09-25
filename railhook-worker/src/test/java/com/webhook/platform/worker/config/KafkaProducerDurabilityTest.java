package com.webhook.platform.worker.config;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import com.webhook.platform.common.dto.DeliveryMessage;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

// The config builds its own property map, so spring.kafka.producer.* in the yaml is inert.
class KafkaProducerDurabilityTest {

    @SuppressWarnings("unchecked")
    private Map<String, Object> producerProperties() {
        KafkaProducerConfig config = new KafkaProducerConfig(new SimpleMeterRegistry());
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
        // retries=3 is ~300ms, shorter than a leader election; delivery.timeout.ms should decide.
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
        // send() runs on a shared @Scheduled thread; the 60s max.block.ms default would pin one per poll.
        Map<String, Object> props = producerProperties();

        assertThat((Integer) props.get(ProducerConfig.MAX_BLOCK_MS_CONFIG)).isLessThanOrEqualTo(10_000);
    }
}

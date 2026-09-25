package com.webhook.platform.worker.config;

import com.webhook.platform.common.dto.DeliveryMessage;
import com.webhook.platform.common.dto.IncomingForwardMessage;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.MicrometerProducerListener;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

@Configuration
public class KafkaProducerConfig {

    private final MeterRegistry meterRegistry;

    public KafkaProducerConfig(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${spring.kafka.producer.delivery-timeout-ms:120000}")
    private int deliveryTimeoutMs;

    @Value("${spring.kafka.producer.max-block-ms:10000}")
    private int maxBlockMs;

    private Map<String, Object> commonProducerProps() {
        Map<String, Object> configProps = new HashMap<>();
        configProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        configProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        configProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        configProps.put(ProducerConfig.ACKS_CONFIG, "all");
        configProps.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        // No RETRIES_CONFIG: the idempotent default retries until delivery.timeout.ms. The old
        // value of 3 gave about 300ms, which a leader election outlasts.
        configProps.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, deliveryTimeoutMs);
        // Bounds the metadata wait of the synchronous outbox send. The 60s default let an
        // unreachable broker take out the shared scheduler pool one thread per poll.
        configProps.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, maxBlockMs);
        return configProps;
    }

    // Spring times template calls only; send rate, queue time and the rest come from this listener.
    private <V> ProducerFactory<String, V> measured(DefaultKafkaProducerFactory<String, V> factory) {
        factory.addListener(new MicrometerProducerListener<>(meterRegistry));
        return factory;
    }

    @Bean
    public ProducerFactory<String, DeliveryMessage> producerFactory() {
        return measured(new DefaultKafkaProducerFactory<>(commonProducerProps()));
    }

    @Bean
    public KafkaTemplate<String, DeliveryMessage> kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }

    @Bean
    public ProducerFactory<String, IncomingForwardMessage> incomingForwardProducerFactory() {
        return measured(new DefaultKafkaProducerFactory<>(commonProducerProps()));
    }

    @Bean
    public KafkaTemplate<String, IncomingForwardMessage> incomingForwardKafkaTemplate() {
        return new KafkaTemplate<>(incomingForwardProducerFactory());
    }

    /**
     * Also carries records that never deserialized, as raw bytes. Through the JSON serializer
     * they became a base64 string and the DLQ no longer held what had been on the topic.
     */
    @Bean(name = "deadLetterKafkaTemplate")
    public KafkaOperations<String, Object> deadLetterKafkaTemplate() {
        Map<Class<?>, Serializer<?>> byType = new LinkedHashMap<>();
        byType.put(byte[].class, new ByteArraySerializer());
        byType.put(Object.class, new JsonSerializer<>());
        ProducerFactory<String, Object> producerFactory = measured(new DefaultKafkaProducerFactory<>(
                commonProducerProps(), new StringSerializer(), new DelegatingByTypeSerializer(byType, true)));
        return new KafkaTemplate<>(producerFactory);
    }
}

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
        // No RETRIES_CONFIG. An idempotent producer defaults to Integer.MAX_VALUE and lets
        // delivery.timeout.ms decide, which is the knob an operator can reason about; the 3 that
        // used to sit here, with the default retry.backoff.ms of 100, was about 300ms of
        // patience. An ordinary leader election or a rolling broker restart outlasts that, so
        // sends failed that the default would have ridden out - and each one costs its outbox
        // row a full retry cycle.
        configProps.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, deliveryTimeoutMs);
        // The outbox publisher calls send() synchronously on a @Scheduled thread and this is
        // what bounds the metadata wait. The default is 60s against a pool of eight threads
        // shared by every scheduled method in the service, so an unreachable broker took them
        // out one per poll.
        configProps.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, maxBlockMs);
        return configProps;
    }

    /**
     * Spring times template calls only. Send rate, queue time, batch size and connections reach
     * Prometheus through this listener or not at all, so every factory here gets one.
     */
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
     * Carries both kinds of dead letter: a record the listener failed on, whose value is a message,
     * and a record whose value never deserialized, which arrives here as the bytes that were read.
     * Those bytes go out as they are; through the JSON serializer they became a base64 string, and
     * the DLQ no longer held what had been on the topic.
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

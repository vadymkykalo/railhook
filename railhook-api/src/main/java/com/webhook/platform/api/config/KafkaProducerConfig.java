package com.webhook.platform.api.config;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class KafkaProducerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${spring.kafka.producer.delivery-timeout-ms:120000}")
    private int deliveryTimeoutMs;

    @Value("${spring.kafka.producer.max-block-ms:10000}")
    private int maxBlockMs;

    @Bean
    public ProducerFactory<String, Object> producerFactory() {
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
        return new DefaultKafkaProducerFactory<>(configProps);
    }

    @Bean
    public KafkaTemplate<String, Object> kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }
}

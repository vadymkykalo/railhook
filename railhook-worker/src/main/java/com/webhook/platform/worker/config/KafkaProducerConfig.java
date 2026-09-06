package com.webhook.platform.worker.config;

import com.webhook.platform.common.dto.DeliveryMessage;
import com.webhook.platform.common.dto.IncomingForwardMessage;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class KafkaProducerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    private Map<String, Object> commonProducerProps() {
        Map<String, Object> configProps = new HashMap<>();
        configProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        configProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        configProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        configProps.put(ProducerConfig.ACKS_CONFIG, "all");
        configProps.put(ProducerConfig.RETRIES_CONFIG, 3);
        configProps.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        return configProps;
    }

    @Bean
    public ProducerFactory<String, DeliveryMessage> producerFactory() {
        return new DefaultKafkaProducerFactory<>(commonProducerProps());
    }

    @Bean
    public KafkaTemplate<String, DeliveryMessage> kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }

    @Bean
    public ProducerFactory<String, IncomingForwardMessage> incomingForwardProducerFactory() {
        return new DefaultKafkaProducerFactory<>(commonProducerProps());
    }

    @Bean
    public KafkaTemplate<String, IncomingForwardMessage> incomingForwardKafkaTemplate() {
        return new KafkaTemplate<>(incomingForwardProducerFactory());
    }

    @Bean(name = "deadLetterKafkaTemplate")
    public KafkaOperations<String, Object> deadLetterKafkaTemplate() {
        ProducerFactory<String, Object> producerFactory = new DefaultKafkaProducerFactory<>(commonProducerProps());
        return new KafkaTemplate<>(producerFactory);
    }
}

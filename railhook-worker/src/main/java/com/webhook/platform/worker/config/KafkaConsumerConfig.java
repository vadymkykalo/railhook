package com.webhook.platform.worker.config;

import com.webhook.platform.common.constants.KafkaTopics;
import com.webhook.platform.common.dto.DeliveryMessage;
import com.webhook.platform.common.dto.IncomingForwardMessage;
import com.webhook.platform.worker.service.ShutdownRejectedException;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.util.backoff.FixedBackOff;

import jakarta.annotation.PostConstruct;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.common.TopicPartition;

@Configuration
@EnableKafka
@Slf4j
public class KafkaConsumerConfig {

    private final KafkaOperations<String, Object> deadLetterKafkaTemplate;

    public KafkaConsumerConfig(@Qualifier("deadLetterKafkaTemplate") KafkaOperations<String, Object> deadLetterKafkaTemplate) {
        this.deadLetterKafkaTemplate = deadLetterKafkaTemplate;
    }

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${spring.kafka.consumer.group-id}")
    private String groupId;

    @Value("${spring.kafka.consumer.incoming-group-id:incoming-forward-worker}")
    private String incomingGroupId;
    
    @Value("${spring.kafka.consumer.max-retries:3}")
    private int maxRetries;
    
    @Value("${spring.kafka.consumer.retry-interval-ms:5000}")
    private long retryIntervalMs;

    @Value("${spring.kafka.consumer.delivery-concurrency:6}")
    private int deliveryConcurrency;

    @Value("${spring.kafka.consumer.incoming-concurrency:3}")
    private int incomingConcurrency;

    @Value("${spring.kafka.consumer.auto-offset-reset:earliest}")
    private String autoOffsetReset;

    @PostConstruct
    void logEffectiveConfig() {
        log.info("Kafka consumer effective config: bootstrapServers={}, groupId={}, incomingGroupId={}, autoOffsetReset={}, deliveryConcurrency={}, incomingConcurrency={}, maxRetries={}, retryIntervalMs={}",
                bootstrapServers, groupId, incomingGroupId, autoOffsetReset, deliveryConcurrency, incomingConcurrency, maxRetries, retryIntervalMs);
    }

    @Bean
    public ConsumerFactory<String, DeliveryMessage> consumerFactory() {
        return buildConsumerFactory(groupId, DeliveryMessage.class);
    }

    @Bean
    public ConsumerFactory<String, IncomingForwardMessage> incomingForwardConsumerFactory() {
        return buildConsumerFactory(incomingGroupId, IncomingForwardMessage.class);
    }

    private <T> ConsumerFactory<String, T> buildConsumerFactory(String consumerGroupId, Class<T> valueType) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, consumerGroupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, autoOffsetReset);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(JsonDeserializer.TRUSTED_PACKAGES, "com.webhook.platform.common.dto");
        props.put(JsonDeserializer.VALUE_DEFAULT_TYPE, valueType.getName());
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 10);
        props.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 300000);
        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, DeliveryMessage> kafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, DeliveryMessage> factory = 
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory());
        configureFactory(factory, deliveryConcurrency, KafkaTopics.DELIVERIES_DLQ);
        return factory;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, IncomingForwardMessage> incomingForwardListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, IncomingForwardMessage> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(incomingForwardConsumerFactory());
        configureFactory(factory, incomingConcurrency, KafkaTopics.INCOMING_FORWARD_DLQ);
        return factory;
    }

    private <K, V> void configureFactory(ConcurrentKafkaListenerContainerFactory<K, V> factory, int concurrency, String dlqTopic) {
        factory.setConcurrency(concurrency);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        // Deliveries are acked from arbitrary BoundedAsyncExecutor pool threads whenever
        // that particular delivery finishes, not in offset order. Plain MANUAL
        // acks commit straight to the highest acked offset regardless of order, so a fast
        // record N acking before a slower record N-k would move the committed offset past
        // still in-flight work — losing N-k on a hard kill. asyncAcks defers out-of-order
        // commits until every lower offset in the batch has also been acked, and pauses
        // the consumer meanwhile so the gap can't grow unbounded.
        factory.getContainerProperties().setAsyncAcks(true);
        factory.getContainerProperties().setShutdownTimeout(30_000L);

        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                deadLetterKafkaTemplate,
                (record, exception) -> new TopicPartition(dlqTopic, record.partition())
        );
        
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(
            recoverer,
            new FixedBackOff(retryIntervalMs, maxRetries)
        );

        // ShutdownRejectedException: no point retrying on a dying instance — send straight to DLQ
        errorHandler.addNotRetryableExceptions(
                ShutdownRejectedException.class
        );

        errorHandler.setRetryListeners((record, ex, deliveryAttempt) ->
                log.warn("Kafka retry attempt {} for topic={}, partition={}, offset={}, key={}, error={}",
                        deliveryAttempt,
                        record.topic(),
                        record.partition(),
                        record.offset(),
                        record.key(),
                        ex.getMessage())
        );
        
        factory.setCommonErrorHandler(errorHandler);
        
        log.info("Kafka consumer configured: dlqTopic={}, concurrency={}, maxRetries={}, retryIntervalMs={}",
                dlqTopic, concurrency, maxRetries, retryIntervalMs);
    }
}

package com.webhook.platform.worker.config;

import com.webhook.platform.common.constants.KafkaTopics;
import com.webhook.platform.common.dto.DeliveryMessage;
import com.webhook.platform.common.dto.IncomingForwardMessage;
import io.micrometer.core.instrument.MeterRegistry;
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
import org.springframework.kafka.core.MicrometerConsumerListener;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.util.backoff.FixedBackOff;

import jakarta.annotation.PostConstruct;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiFunction;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;

@Configuration
@EnableKafka
@Slf4j
public class KafkaConsumerConfig {

    private final KafkaOperations<String, Object> deadLetterKafkaTemplate;
    private final MeterRegistry meterRegistry;

    public KafkaConsumerConfig(@Qualifier("deadLetterKafkaTemplate") KafkaOperations<String, Object> deadLetterKafkaTemplate,
                               MeterRegistry meterRegistry) {
        this.deadLetterKafkaTemplate = deadLetterKafkaTemplate;
        this.meterRegistry = meterRegistry;
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
        // A value that fails to parse inside poll() has no record to dead-letter, so it was
        // re-polled forever. Wrapped, it arrives as a null value the error handler can park.
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        props.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, JsonDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, autoOffsetReset);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(JsonDeserializer.TRUSTED_PACKAGES, "com.webhook.platform.common.dto");
        props.put(JsonDeserializer.VALUE_DEFAULT_TYPE, valueType.getName());
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 10);
        props.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 300000);
        DefaultKafkaConsumerFactory<String, T> factory = new DefaultKafkaConsumerFactory<>(props);
        // Spring times only the listener call; lag, fetch latency and the rest come from here.
        factory.addListener(new MicrometerConsumerListener<>(meterRegistry));
        return factory;
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

    // Leaves the partition to the broker: copying the source partition fails once the main topic
    // has more partitions than its DLQ, and the record is lost. The key still groups a delivery.
    static BiFunction<ConsumerRecord<?, ?>, Exception, TopicPartition> dlqDestination(String dlqTopic) {
        TopicPartition destination = new TopicPartition(dlqTopic, -1);
        return (record, exception) -> destination;
    }

    private <K, V> void configureFactory(ConcurrentKafkaListenerContainerFactory<K, V> factory, int concurrency, String dlqTopic) {
        factory.setConcurrency(concurrency);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        // Acks arrive from pool threads out of offset order. Plain MANUAL would commit past a
        // slower record still in flight and lose it on a hard kill; asyncAcks waits for every
        // lower offset and pauses the consumer meanwhile.
        factory.getContainerProperties().setAsyncAcks(true);
        factory.getContainerProperties().setShutdownTimeout(30_000L);

        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                deadLetterKafkaTemplate,
                dlqDestination(dlqTopic));
        
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(
            recoverer,
            new FixedBackOff(retryIntervalMs, maxRetries)
        );
        // No seek after an error: records behind a seek wait in asyncAcks' pending list for an
        // ack that never comes while the partition is paused, until a rebalance.
        errorHandler.setSeekAfterError(false);

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

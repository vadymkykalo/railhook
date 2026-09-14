package com.webhook.platform.worker.config;

import com.webhook.platform.common.constants.KafkaTopics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;

import java.util.List;
import java.util.stream.Stream;

/**
 * Declares every topic the platform uses, so the worker's KafkaAdmin creates any that are missing
 * each time it starts.
 *
 * <p>kafka-init creates them once, and nothing checked again. When the broker lost its log, the
 * topics something wrote to came back by auto-creation and the DLQ topics, written only when a
 * message is abandoned, did not — the worker then failed every minute to read their depth, and an
 * abandoned message had nowhere to go. Existing topics are left as they are; KafkaAdmin only adds
 * partitions when the declared count is higher, which is why the count here is the operator's.
 */
@Configuration
public class KafkaTopicsConfig {

    private static final int REPLICAS = 1;

    @Bean
    public KafkaAdmin.NewTopics railhookTopics(
            @Value("${spring.kafka.num-partitions:12}") int partitions) {
        return new KafkaAdmin.NewTopics(topics(partitions).toArray(NewTopic[]::new));
    }

    static List<NewTopic> topics(int partitions) {
        return Stream.of(
                        KafkaTopics.DELIVERIES_DISPATCH,
                        KafkaTopics.DELIVERIES_RETRY_1M, KafkaTopics.DELIVERIES_RETRY_5M,
                        KafkaTopics.DELIVERIES_RETRY_15M, KafkaTopics.DELIVERIES_RETRY_1H,
                        KafkaTopics.DELIVERIES_RETRY_6H, KafkaTopics.DELIVERIES_RETRY_24H,
                        KafkaTopics.DELIVERIES_DLQ,
                        KafkaTopics.INCOMING_FORWARD_DISPATCH, KafkaTopics.INCOMING_FORWARD_RETRY,
                        KafkaTopics.INCOMING_FORWARD_DLQ)
                .map(name -> TopicBuilder.name(name).partitions(partitions).replicas(REPLICAS).build())
                .toList();
    }
}

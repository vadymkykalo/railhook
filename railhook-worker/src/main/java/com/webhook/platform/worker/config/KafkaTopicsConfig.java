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
 * Declared so a missing topic is recreated on every start. After a broker lost its log, the DLQ
 * topics, written only on abandonment, never came back by auto-creation. KafkaAdmin never shrinks
 * an existing topic, so the partition count here is the operator's.
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

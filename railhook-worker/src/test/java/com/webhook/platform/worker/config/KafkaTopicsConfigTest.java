package com.webhook.platform.worker.config;

import com.webhook.platform.common.constants.KafkaTopics;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every topic the platform uses exists whenever the worker is up, whatever happened to the broker.
 *
 * <p>Topics were created once, by the kafka-init container, and nothing checked them again. On
 * production the broker's log did not live on its volume, so recreating the Kafka container threw
 * the topics away; dispatch and retry came back through auto-creation the first time something
 * wrote to them, and the two DLQ topics — written only when a message is abandoned — never did.
 * The worker declares all of them, so a missing one is created on its next start.
 */
class KafkaTopicsConfigTest {

    @Test
    void declaresEveryTopicWithTheConfiguredPartitionCount() {
        Map<String, NewTopic> byName = KafkaTopicsConfig.topics(12).stream()
                .collect(Collectors.toMap(NewTopic::name, Function.identity()));
        assertThat(byName.keySet()).containsExactlyInAnyOrder(
                KafkaTopics.DELIVERIES_DISPATCH,
                KafkaTopics.DELIVERIES_RETRY_1M, KafkaTopics.DELIVERIES_RETRY_5M,
                KafkaTopics.DELIVERIES_RETRY_15M, KafkaTopics.DELIVERIES_RETRY_1H,
                KafkaTopics.DELIVERIES_RETRY_6H, KafkaTopics.DELIVERIES_RETRY_24H,
                KafkaTopics.DELIVERIES_DLQ,
                KafkaTopics.INCOMING_FORWARD_DISPATCH, KafkaTopics.INCOMING_FORWARD_RETRY,
                KafkaTopics.INCOMING_FORWARD_DLQ);
        assertThat(byName.values()).allSatisfy(topic -> assertThat(topic.numPartitions()).isEqualTo(12));
    }
}

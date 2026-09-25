package com.webhook.platform.worker.config;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Copying the source partition lost every dead letter once a main topic outgrew its DLQ. */
class DlqDestinationTest {

    private static ConsumerRecord<String, String> recordOnPartition(int partition) {
        return new ConsumerRecord<>("deliveries", partition, 0L, "delivery-key", "payload");
    }

    private static TopicPartition resolve(String dlqTopic, int sourcePartition) {
        BiFunction<ConsumerRecord<?, ?>, Exception, TopicPartition> resolver =
                KafkaConsumerConfig.dlqDestination(dlqTopic);
        return resolver.apply(recordOnPartition(sourcePartition), new IllegalStateException("failed for the last time"));
    }

    @Test
    @DisplayName("the broker picks the partition, so a smaller DLQ cannot swallow a dead letter")
    void partitionIsLeftToTheBroker() {
        for (int sourcePartition : new int[]{0, 11, 47}) {
            TopicPartition destination = resolve("deliveries.dlq", sourcePartition);

            assertEquals("deliveries.dlq", destination.topic());
            assertTrue(destination.partition() < 0, "source partition " + sourcePartition + " was carried over");
        }
    }

    @Test
    @DisplayName("each direction still parks in its own topic")
    void topicIsPreserved() {
        assertEquals("incoming.forward.dlq", resolve("incoming.forward.dlq", 3).topic());
    }
}

package com.webhook.platform.worker.config;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where a record goes once it has failed for the last time.
 *
 * <p>This used to copy the source record's partition onto the DLQ topic, which is only ever
 * correct while the DLQ has at least as many partitions as the topic it shadows. That holds
 * today by coincidence — docker-compose.yml creates every topic in one loop with the same
 * KAFKA_NUM_PARTITIONS — and stops holding the first time somebody repartitions a main topic
 * upward to scale its consumers, which is the ordinary thing to do. From then on every dead
 * letter out of a partition the DLQ does not have fails to publish, and a message that can
 * neither be retried nor parked is simply gone.
 */
class DlqDestinationTest {

    private static ConsumerRecord<String, String> recordOnPartition(int partition) {
        return new ConsumerRecord<>("deliveries", partition, 0L, "delivery-key", "payload");
    }

    @Test
    @DisplayName("the broker picks the partition, so a smaller DLQ cannot swallow a dead letter")
    void partitionIsLeftToTheBroker() {
        TopicPartition destination = KafkaConsumerConfig.dlqDestination("deliveries.dlq", recordOnPartition(11));

        assertEquals("deliveries.dlq", destination.topic());
        assertTrue(destination.partition() < 0,
                "a negative partition is how the producer is told to choose; pinning one assumes "
                        + "the DLQ is at least as wide as the topic it shadows, and nothing enforces that");
    }

    @Test
    @DisplayName("the source partition is not carried over, whichever it was")
    void sourcePartitionIsIgnored() {
        int high = KafkaConsumerConfig.dlqDestination("deliveries.dlq", recordOnPartition(47)).partition();
        int low = KafkaConsumerConfig.dlqDestination("deliveries.dlq", recordOnPartition(0)).partition();

        assertEquals(low, high, "the destination must not depend on where the record came from");
    }

    @Test
    @DisplayName("each direction still parks in its own topic")
    void topicIsPreserved() {
        assertEquals("incoming.forward.dlq",
                KafkaConsumerConfig.dlqDestination("incoming.forward.dlq", recordOnPartition(3)).topic());
    }
}

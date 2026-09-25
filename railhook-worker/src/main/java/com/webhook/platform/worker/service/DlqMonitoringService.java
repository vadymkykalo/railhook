package com.webhook.platform.worker.service;

import com.webhook.platform.common.constants.KafkaTopics;
import com.webhook.platform.worker.domain.repository.DeliveryRepository;
import com.webhook.platform.worker.domain.repository.IncomingForwardAttemptRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.TopicPartitionInfo;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * The {@code *_dlq_depth} gauges count rows in DLQ status in Postgres and return to zero once the
 * backlog is worked through; alert on those. The {@code *_dlq_topic_retained_total} gauges count
 * records the Kafka topics still retain, which nothing consumes, so they stay up for the whole
 * retention window. Never alert on them: the Kafka-based gauge they replaced fired forever.
 */
@Service
@Slf4j
public class DlqMonitoringService {

    private final AdminClient adminClient;
    private final DeliveryRepository deliveryRepository;
    private final IncomingForwardAttemptRepository incomingForwardAttemptRepository;
    private final AtomicLong actionableDlqDepth = new AtomicLong(0);
    private final AtomicLong topicRetainedDepth = new AtomicLong(0);
    private final AtomicLong incomingActionableDlqDepth = new AtomicLong(0);
    private final AtomicLong incomingTopicRetainedDepth = new AtomicLong(0);
    private final long adminClientTimeoutSeconds;

    public DlqMonitoringService(
            KafkaAdmin kafkaAdmin,
            DeliveryRepository deliveryRepository,
            IncomingForwardAttemptRepository incomingForwardAttemptRepository,
            MeterRegistry meterRegistry,
            @Value("${dlq.monitoring.admin-client-timeout-seconds:10}") long adminClientTimeoutSeconds) {
        this.adminClient = AdminClient.create(kafkaAdmin.getConfigurationProperties());
        this.deliveryRepository = deliveryRepository;
        this.incomingForwardAttemptRepository = incomingForwardAttemptRepository;
        this.adminClientTimeoutSeconds = adminClientTimeoutSeconds;

        Gauge.builder("webhook_dlq_depth", actionableDlqDepth, AtomicLong::get)
                .description("Deliveries currently in DLQ status awaiting manual retry or purge (actionable backlog)")
                .tag("topic", KafkaTopics.DELIVERIES_DLQ)
                .register(meterRegistry);

        Gauge.builder("webhook_dlq_topic_retained_total", topicRetainedDepth, AtomicLong::get)
                .description("Raw records retained in the DLQ Kafka topic (latest - earliest offset); informational only, does not reflect remediation")
                .tag("topic", KafkaTopics.DELIVERIES_DLQ)
                .register(meterRegistry);

        Gauge.builder("incoming_forward_dlq_depth", incomingActionableDlqDepth, AtomicLong::get)
                .description("Forwards currently in DLQ status awaiting manual retry or purge (actionable backlog)")
                .tag("topic", KafkaTopics.INCOMING_FORWARD_DLQ)
                .register(meterRegistry);

        Gauge.builder("incoming_forward_dlq_topic_retained_total", incomingTopicRetainedDepth, AtomicLong::get)
                .description("Raw records retained in the incoming forward DLQ Kafka topic (latest - earliest offset); informational only, does not reflect remediation")
                .tag("topic", KafkaTopics.INCOMING_FORWARD_DLQ)
                .register(meterRegistry);

        log.info("DLQ monitoring initialized");
    }

    @PreDestroy
    public void close() {
        try {
            adminClient.close();
            log.info("DLQ monitoring AdminClient closed");
        } catch (Exception e) {
            log.warn("Failed to close DLQ AdminClient: {}", e.getMessage());
        }
    }

    @Scheduled(fixedDelayString = "${dlq.monitoring.interval-ms:60000}")
    public void monitorDlqDepth() {
        // Separate try/catches: a DB outage must not hide the Kafka numbers, a broker outage the
        // DB ones, or one direction's failure the other's backlog.
        refreshActionableDepth("Deliveries", deliveryRepository::countDlqTotal, actionableDlqDepth);
        refreshActionableDepth("Forwards", incomingForwardAttemptRepository::countDlqTotal, incomingActionableDlqDepth);
        refreshTopicRetainedDepth(KafkaTopics.DELIVERIES_DLQ, topicRetainedDepth);
        refreshTopicRetainedDepth(KafkaTopics.INCOMING_FORWARD_DLQ, incomingTopicRetainedDepth);
    }

    private void refreshActionableDepth(String what, LongSupplier count, AtomicLong gaugeValue) {
        try {
            long depth = count.getAsLong();
            long previous = gaugeValue.getAndSet(depth);

            if (depth > 0) {
                log.warn("{} {} in DLQ status - manual intervention may be required (retry or purge via DlqService)", depth, what);
            } else if (previous > 0) {
                log.info("DLQ backlog cleared - 0 {} remain in DLQ status", what);
            }
        } catch (Exception e) {
            log.error("Failed to refresh actionable DLQ depth for {}: {}", what, e.getMessage());
        }
    }

    private void refreshTopicRetainedDepth(String topic, AtomicLong gaugeValue) {
        try {
            // Bounded get(): an unbounded one blocked a shared scheduler thread on a slow broker.
            TopicDescription description = adminClient
                    .describeTopics(Collections.singletonList(topic))
                    .topicNameValues()
                    .get(topic)
                    .get(adminClientTimeoutSeconds, TimeUnit.SECONDS);

            List<TopicPartitionInfo> partitions = description.partitions();

            Map<TopicPartition, OffsetSpec> latestRequest = new HashMap<>();
            Map<TopicPartition, OffsetSpec> earliestRequest = new HashMap<>();
            for (TopicPartitionInfo partitionInfo : partitions) {
                TopicPartition tp = new TopicPartition(topic, partitionInfo.partition());
                latestRequest.put(tp, OffsetSpec.latest());
                earliestRequest.put(tp, OffsetSpec.earliest());
            }

            Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> latestOffsets = adminClient
                    .listOffsets(latestRequest).all().get(adminClientTimeoutSeconds, TimeUnit.SECONDS);
            Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> earliestOffsets = adminClient
                    .listOffsets(earliestRequest).all().get(adminClientTimeoutSeconds, TimeUnit.SECONDS);

            long totalMessages = 0;
            for (TopicPartitionInfo partitionInfo : partitions) {
                TopicPartition tp = new TopicPartition(topic, partitionInfo.partition());
                long latest = latestOffsets.get(tp).offset();
                long earliest = earliestOffsets.get(tp).offset();
                long partitionDepth = latest - earliest;
                totalMessages += partitionDepth;

                if (partitionDepth > 0) {
                    log.debug("DLQ topic {} partition {} retained: {} (earliest={}, latest={})",
                            topic, partitionInfo.partition(), partitionDepth, earliest, latest);
                }
            }

            gaugeValue.set(totalMessages);
        } catch (Exception e) {
            log.error("Failed to monitor DLQ topic retained depth for {}: {}", topic, e.getMessage());
        }
    }
}

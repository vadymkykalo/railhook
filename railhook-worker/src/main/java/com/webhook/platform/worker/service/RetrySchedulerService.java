package com.webhook.platform.worker.service;

import com.webhook.platform.common.constants.KafkaTopics;
import com.webhook.platform.common.dto.DeliveryMessage;
import com.webhook.platform.worker.domain.entity.Delivery;
import com.webhook.platform.worker.domain.repository.DeliveryRepository;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.producer.RecordMetadata;
import org.springframework.kafka.support.SendResult;

@Service
@Slf4j
public class RetrySchedulerService {

    private final DeliveryRepository deliveryRepository;
    private final KafkaTemplate<String, DeliveryMessage> kafkaTemplate;
    private final TransactionTemplate transactionTemplate;
    private final CircuitBreakerService circuitBreakerService;
    private final int maxPerEndpoint;
    private final int maxPerProject;
    private final long sendTimeoutSeconds;
    private final long rescheduleDelaySeconds;
    private final long defaultPollIntervalMs;
    private final RetryGovernor governor;
    private final AdaptivePollLoop pollLoop;

    public RetrySchedulerService(
            DeliveryRepository deliveryRepository,
            KafkaTemplate<String, DeliveryMessage> kafkaTemplate,
            TransactionTemplate transactionTemplate,
            CircuitBreakerService circuitBreakerService,
            MeterRegistry meterRegistry,
            @Value("${retry.scheduler.batch-size:100}") int batchSize,
            @Value("${retry.scheduler.max-per-endpoint:10}") int maxPerEndpoint,
            @Value("${retry.scheduler.max-per-project:30}") int maxPerProject,
            @Value("${retry.scheduler.send-timeout-seconds:30}") long sendTimeoutSeconds,
            @Value("${retry.scheduler.reschedule-delay-seconds:60}") long rescheduleDelaySeconds,
            @Value("${retry.scheduler.high-watermark:5000}") long highWatermark,
            @Value("${retry.scheduler.poll-interval-ms:10000}") long defaultPollIntervalMs) {
        this.deliveryRepository = deliveryRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.transactionTemplate = transactionTemplate;
        this.circuitBreakerService = circuitBreakerService;
        this.maxPerEndpoint = maxPerEndpoint;
        this.maxPerProject = maxPerProject;
        this.sendTimeoutSeconds = sendTimeoutSeconds;
        this.rescheduleDelaySeconds = rescheduleDelaySeconds;
        this.defaultPollIntervalMs = defaultPollIntervalMs;
        this.governor = new RetryGovernor(
                "outgoing", batchSize, /* minBatch */ 5, /* increment */ 10,
                highWatermark, /* maxCooldownPolls */ 6, meterRegistry);
        this.pollLoop = new AdaptivePollLoop("retry-scheduler", governor,
                defaultPollIntervalMs, this::countPendingRetries, this::scheduleRetries);
    }

    @PostConstruct
    void startScheduler() {
        pollLoop.start();
    }

    @PreDestroy
    void stopScheduler() {
        pollLoop.stop();
    }

    void scheduleRetries(long pendingCount) {
        int effectiveBatch = governor.computeEffectiveBatch(pendingCount);
        if (effectiveBatch <= 0) {
            return; // Governor cooldown — skip this poll
        }

        // Phase 1: short transaction, claim candidates.
        List<Delivery> claimed = transactionTemplate.execute(tx -> {
            Instant now = Instant.now();

            List<UUID> candidateIds = deliveryRepository.findPendingRetryIds(
                    Delivery.DeliveryStatus.PENDING, now, effectiveBatch, maxPerEndpoint, maxPerProject);
            if (candidateIds.isEmpty()) {
                return List.<Delivery>of();
            }

            List<Delivery> locked = deliveryRepository.lockByIds(candidateIds);
            if (locked.isEmpty()) {
                return List.<Delivery>of();
            }

            // PROCESSING so a crash before Phase 3 leaves a row the stuck sweep can recover,
            // rather than a PENDING row with no next_retry_at that nothing can see.
            for (Delivery d : locked) {
                // Fresh token per claim, so an earlier attempt swept away as abandoned
                // cannot come back and write over this one.
                d.claim(UUID.randomUUID());
            }
            deliveryRepository.saveAll(locked);

            return locked;
        });

        if (claimed == null || claimed.isEmpty()) {
            return;
        }

        log.info("Claimed {} deliveries for retry dispatch", claimed.size());

        // Phase 2: outside the transaction, Kafka I/O.
        Map<UUID, CompletableFuture<SendResult<String, DeliveryMessage>>> futures = new HashMap<>();
        Map<UUID, String> deliveryTopics = new HashMap<>();
        List<Delivery> circuitBreakerSkipped = new ArrayList<>();

        for (Delivery delivery : claimed) {
            if (!circuitBreakerService.isCallPermitted(delivery.getEndpointId())) {
                rescheduleDelivery(delivery, "Circuit breaker OPEN");
                circuitBreakerSkipped.add(delivery);
                log.info("Skipped retry for delivery {} - circuit breaker OPEN for endpoint {}",
                        delivery.getId(), delivery.getEndpointId());
                continue;
            }
            
            try {
                String topic = getRetryTopic(delivery.getAttemptCount());
                deliveryTopics.put(delivery.getId(), topic);

                DeliveryMessage message = DeliveryMessage.builder()
                        .deliveryId(delivery.getId())
                        .eventId(delivery.getEventId())
                        .endpointId(delivery.getEndpointId())
                        .subscriptionId(delivery.getSubscriptionId())
                        .status(delivery.getStatus().name())
                        .attemptCount(delivery.getAttemptCount())
                        // The consumer CASes on this rather than trusting the status: a
                        // redelivered message and a timed-out send both see PROCESSING.
                        .claimToken(delivery.getClaimToken())
                        .build();

                CompletableFuture<SendResult<String, DeliveryMessage>> future = kafkaTemplate.send(topic,
                        delivery.getEndpointId().toString(), message);
                futures.put(delivery.getId(), future);
            } catch (Exception e) {
                log.error("Failed to initiate send for delivery {}: {}", delivery.getId(), e.getMessage(), e);
            }
        }

        if (futures.isEmpty()) {
            rescheduleAll(claimed, "Send not initiated");
            return;
        }

        try {
            CompletableFuture.allOf(futures.values().toArray(new CompletableFuture[0]))
                    .get(sendTimeoutSeconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("Batch send timeout or error, will check individual results: {}", e.getMessage());
        }

        // Phase 3: short transaction, update results. Only rows this method still owns are
        // written — a successful send hands the row to the consumer, so it is counted, not saved.
        int sentDeliveries = 0;
        List<Delivery> failedDeliveries = new ArrayList<>();

        for (Delivery delivery : claimed) {
            CompletableFuture<SendResult<String, DeliveryMessage>> future = futures.get(delivery.getId());
            if (future == null) {
                rescheduleDelivery(delivery, "Send not initiated");
                failedDeliveries.add(delivery);
                continue;
            }

            try {
                if (!future.isDone()) {
                    rescheduleDelivery(delivery, "Send confirmation timeout");
                    failedDeliveries.add(delivery);
                    continue;
                }
                SendResult<String, DeliveryMessage> result = future.get();
                RecordMetadata metadata = result.getRecordMetadata();
                // Deliberately NOT collected for saving. The consumer often picks the message
                // up within milliseconds and has already advanced the row; re-saving the Phase 1
                // snapshot raced it, and when the consumer lost, the retry partition stalled
                // until a restart.
                sentDeliveries++;

                log.info("Scheduled retry for delivery {} to topic {} partition {} offset {}",
                        delivery.getId(),
                        deliveryTopics.get(delivery.getId()),
                        metadata.partition(),
                        metadata.offset());
            } catch (Exception e) {
                rescheduleDelivery(delivery, e.getMessage());
                failedDeliveries.add(delivery);

                log.error("Kafka send failed for delivery {} eventId={} endpointId={}: {}",
                        delivery.getId(),
                        delivery.getEventId(),
                        delivery.getEndpointId(),
                        e.getMessage(), e);
            }
        }

        transactionTemplate.executeWithoutResult(tx -> {
            if (!failedDeliveries.isEmpty()) {
                deliveryRepository.saveAll(failedDeliveries);
            }
            if (!circuitBreakerSkipped.isEmpty()) {
                deliveryRepository.saveAll(circuitBreakerSkipped);
            }
        });

        governor.recordResult(sentDeliveries, failedDeliveries.size());

        log.info("Retry scheduling complete: {} successful, {} failed/rescheduled, {} circuit-breaker-skipped (governor batch={}, pendingCount={})",
                sentDeliveries, failedDeliveries.size(), circuitBreakerSkipped.size(), 
                effectiveBatch, pendingCount);
    }

    private void rescheduleAll(List<Delivery> deliveries, String reason) {
        for (Delivery d : deliveries) {
            rescheduleDelivery(d, reason);
        }
        transactionTemplate.executeWithoutResult(tx -> deliveryRepository.saveAll(deliveries));
    }

    private void rescheduleDelivery(Delivery delivery, String reason) {
        long jitter = ThreadLocalRandom.current().nextLong(0, Math.max(1, rescheduleDelaySeconds / 2) + 1);
        Instant rescheduleTime = Instant.now().plusSeconds(rescheduleDelaySeconds + jitter);
        // Revert the Phase 1 claim so the delivery is picked up again rather than waiting
        // out a stuck sweep.
        delivery.handBackTo(rescheduleTime);

        log.warn("Rescheduling delivery {} to {} due to: {}",
                delivery.getId(), rescheduleTime, reason);
    }

    private long countPendingRetries() {
        try {
            return deliveryRepository.countPending(Instant.now().minus(30, ChronoUnit.DAYS));
        } catch (Exception e) {
            log.warn("Failed to count pending retries for governor: {}", e.getMessage());
            return -1; // Unknown — governor skips queue depth check
        }
    }

    private String getRetryTopic(int attemptCount) {
        return switch (attemptCount) {
            // 0: concurrency/rate-limit backpressure reschedules the delivery before its
            // first HTTP attempt (WebhookDeliveryService increments attempt_count only once
            // the call is actually about to be made) -- treat it the same as attempt 1, not
            // as an exhausted ladder.
            case 0, 1 -> KafkaTopics.DELIVERIES_RETRY_1M;
            case 2 -> KafkaTopics.DELIVERIES_RETRY_5M;
            case 3 -> KafkaTopics.DELIVERIES_RETRY_15M;
            case 4 -> KafkaTopics.DELIVERIES_RETRY_1H;
            case 5 -> KafkaTopics.DELIVERIES_RETRY_6H;
            default -> KafkaTopics.DELIVERIES_RETRY_24H;
        };
    }
}

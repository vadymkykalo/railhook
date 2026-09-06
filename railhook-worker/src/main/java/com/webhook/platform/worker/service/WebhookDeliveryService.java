package com.webhook.platform.worker.service;

import com.webhook.platform.common.dto.DeliveryMessage;
import com.webhook.platform.worker.attempt.AttemptRunner;
import com.webhook.platform.worker.attempt.DeliveryAttemptMetrics;
import com.webhook.platform.worker.attempt.OutgoingAttemptStoreFactory;
import com.webhook.platform.worker.domain.entity.Delivery;
import com.webhook.platform.worker.domain.repository.DeliveryRepository;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The Outgoing half of the pipeline: run an Attempt, and hand a Delivery back to the retry ladder
 * when there is no room to run one.
 *
 * <p>Everything about how an Attempt happens is behind the Runner, and everything about how the
 * Outgoing direction records one is behind its store.
 */
@Service
@Slf4j
public class WebhookDeliveryService {

    private final AttemptRunner attemptRunner;
    private final OutgoingAttemptStoreFactory storeFactory;
    private final DeliveryAttemptMetrics metrics;
    private final DeliveryRepository deliveryRepository;
    private final TransactionTemplate transactionTemplate;

    private final AtomicInteger inFlightCount = new AtomicInteger(0);
    private volatile boolean shuttingDown = false;

    public WebhookDeliveryService(
            AttemptRunner attemptRunner,
            OutgoingAttemptStoreFactory storeFactory,
            DeliveryAttemptMetrics metrics,
            DeliveryRepository deliveryRepository,
            TransactionTemplate transactionTemplate) {
        this.attemptRunner = attemptRunner;
        this.storeFactory = storeFactory;
        this.metrics = metrics;
        this.deliveryRepository = deliveryRepository;
        this.transactionTemplate = transactionTemplate;
    }

    @PreDestroy
    public void onShutdown() {
        shuttingDown = true;
        log.info("Graceful shutdown initiated, {} in-flight deliveries (handled by Kafka container shutdown)",
                inFlightCount.get());
    }

    public boolean isShuttingDown() {
        return shuttingDown;
    }

    public void processDelivery(DeliveryMessage message, boolean isRetry) {
        inFlightCount.incrementAndGet();
        try {
            attemptRunner.run(storeFactory.create(message, isRetry), metrics);
        } catch (Exception e) {
            log.error("Unexpected error in delivery {}: {}", message.getDeliveryId(), e.getMessage(), e);
        } finally {
            inFlightCount.decrementAndGet();
        }
    }

    /**
     * Called when the async executor pool is full and this record cannot even be submitted.
     *
     * <p>An unacked record is not redelivered until a rebalance, and since commits are deferred
     * until every lower offset is acked, leaving it unacked stalls the whole partition rather than
     * merely delaying it. Kafka's job for the record is done either way — the retry ladder, not
     * redelivery, drives reprocessing — so the row is rescheduled and the caller acks.
     */
    public void rescheduleForBackpressure(UUID deliveryId, boolean isRetry) {
        transactionTemplate.executeWithoutResult(tx -> {
            Delivery delivery = deliveryRepository.findById(deliveryId).orElse(null);
            if (delivery == null) {
                log.debug("Delivery {} disappeared before backpressure reschedule", deliveryId);
                return;
            }
            Delivery.DeliveryStatus expected = isRetry
                    ? Delivery.DeliveryStatus.PROCESSING
                    : Delivery.DeliveryStatus.PENDING;
            if (delivery.getStatus() != expected) {
                log.debug("Delivery {} no longer {} (already handled?), skipping backpressure reschedule",
                        deliveryId, expected);
                return;
            }
            long delaySec = ThreadLocalRandom.current().nextLong(5, 16);
            delivery.handBackTo(Instant.now().plusSeconds(delaySec));
            deliveryRepository.save(delivery);
            log.warn("Executor pool full, rescheduled delivery {} via retry ladder in {}s instead of leaving it unacked",
                    deliveryId, delaySec);
        });
    }
}

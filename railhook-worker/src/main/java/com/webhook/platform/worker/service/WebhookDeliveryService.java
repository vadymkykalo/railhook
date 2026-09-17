package com.webhook.platform.worker.service;

import com.webhook.platform.common.dto.DeliveryMessage;
import com.webhook.platform.worker.attempt.AttemptRunner;
import com.webhook.platform.worker.attempt.DeliveryAttemptMetrics;
import com.webhook.platform.worker.attempt.OutgoingAttemptStoreFactory;
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

    /**
     * Only reports. By the time this runs the Kafka containers have already stopped — they stop
     * in the lifecycle phase, before any {@code @PreDestroy} — so no new record can arrive, and
     * the in-flight ones are drained by the executor pools after this. A record polled but not
     * acked is redelivered from the committed offset by whichever consumer takes the partition.
     */
    @PreDestroy
    public void onShutdown() {
        log.info("Graceful shutdown: {} in-flight deliveries left for the executor pools to drain",
                inFlightCount.get());
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
     *
     * <p>Only the Claim this message would have taken is handed back. Kafka delivers a message
     * more than once, and another copy may hold the row with its POST on the wire: taken from it,
     * that copy's 2xx could not finalise and the ladder sent the webhook again. So a retry
     * message hands back only while the row still carries the token it was published with, and a
     * dispatch message only a row nobody has claimed. A retry message without a token cannot tell
     * which copy it is, and leaves the row to the stuck sweep.
     */
    public void rescheduleForBackpressure(DeliveryMessage message, boolean isRetry) {
        UUID deliveryId = message.getDeliveryId();
        long delaySec = ThreadLocalRandom.current().nextLong(5, 16);
        Instant retryAt = Instant.now().plusSeconds(delaySec);

        if (isRetry && message.getClaimToken() == null) {
            log.warn("Executor pool full for retry delivery {} with no claim token; leaving it to the stuck sweep",
                    deliveryId);
            return;
        }
        Integer written = transactionTemplate.execute(tx -> isRetry
                ? deliveryRepository.handBackIfStillClaimed(deliveryId, message.getClaimToken(), retryAt)
                : deliveryRepository.scheduleIfUnclaimed(deliveryId, retryAt));
        if (written == null || written == 0) {
            log.debug("Delivery {} is not in the state this message would claim (another copy holds it, "
                    + "or it is done), skipping backpressure reschedule", deliveryId);
            return;
        }
        log.warn("Executor pool full, rescheduled delivery {} via retry ladder in {}s instead of leaving it unacked",
                deliveryId, delaySec);
    }
}

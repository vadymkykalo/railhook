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

    // Only reports. The Kafka containers stop in the lifecycle phase, before any @PreDestroy,
    // and the executor pools drain what is in flight after this.
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

    // Called when the pool is full. Hands back only the Claim this message would have taken:
    // another copy may hold the row with its POST on the wire, and taking it sent the webhook twice.
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

package com.webhook.platform.worker.attempt;

import com.webhook.platform.common.constants.KafkaTopics;
import com.webhook.platform.common.dto.DeliveryMessage;
import com.webhook.platform.worker.domain.entity.Delivery;
import com.webhook.platform.worker.domain.repository.DeliveryRepository;
import com.webhook.platform.worker.service.OrderingBufferService;
import io.micrometer.core.instrument.Counter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * FIFO for one endpoint: whether a Delivery's turn has come, and what to let through once it is
 * done. Only the Outgoing direction has this; the Incoming direction enforces no ordering.
 */
@Slf4j
class OrderingGate {

    private final OrderingBufferService orderingBufferService;
    private final DeliveryRepository deliveryRepository;
    private final KafkaTemplate<String, DeliveryMessage> kafkaTemplate;
    private final Counter gapTimeoutCounter;
    private final long rescheduleDelaySeconds;

    OrderingGate(OrderingBufferService orderingBufferService,
            DeliveryRepository deliveryRepository,
            KafkaTemplate<String, DeliveryMessage> kafkaTemplate,
            Counter gapTimeoutCounter,
            long rescheduleDelaySeconds) {
        this.orderingBufferService = orderingBufferService;
        this.deliveryRepository = deliveryRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.gapTimeoutCounter = gapTimeoutCounter;
        this.rescheduleDelaySeconds = rescheduleDelaySeconds;
    }

    /**
     * @return when to come back, or null if this Delivery may proceed now. Parking hands the row
     *         back to the retry ladder, so the Claim is over — the token is cleared rather than
     *         left stale for a later writer to match.
     */
    Instant holdUntil(Delivery delivery) {
        UUID endpointId = delivery.getEndpointId();
        long sequenceNumber = delivery.getSequenceNumber();

        if (orderingBufferService.canDeliver(endpointId, sequenceNumber)) {
            return null;
        }

        // The whole missing range: checking only sequenceNumber - 1 let a Delivery several
        // ahead sail through whenever the immediately preceding one was already terminal.
        Long lastDelivered = orderingBufferService.getLastDeliveredSequence(endpointId);
        long rangeStart = (lastDelivered == null ? 0 : lastDelivered) + 1;
        long rangeEnd = sequenceNumber - 1;

        Instant oldestPendingInRange = rangeStart <= rangeEnd
                ? deliveryRepository.findOldestPendingCreatedAt(endpointId, rangeStart, rangeEnd)
                : null;

        if (oldestPendingInRange == null) {
            log.info("No outstanding deliveries in gap [{}, {}] for endpoint {}, proceeding with seq={}",
                    rangeStart, rangeEnd, endpointId, sequenceNumber);
            return null;
        }

        // From when this Delivery was first buffered: the blocking row's ingest timestamp made
        // the timeout trivially true for any backlog older than it.
        if (orderingBufferService.isGapTimedOut(delivery.getOrderingFirstBufferedAt())) {
            log.warn("Gap timeout for endpoint {}, proceeding with seq={} despite outstanding range [{}, {}]",
                    endpointId, sequenceNumber, rangeStart, rangeEnd);
            gapTimeoutCounter.increment();
            return null;
        }

        return park(delivery, endpointId, sequenceNumber, rangeStart, rangeEnd);
    }

    private Instant park(Delivery delivery, UUID endpointId, long sequenceNumber, long rangeStart, long rangeEnd) {
        if (delivery.getOrderingFirstBufferedAt() == null) {
            delivery.setOrderingFirstBufferedAt(Instant.now());
        }
        log.info("Buffering delivery {} (seq={}) waiting for range [{}, {}]",
                delivery.getId(), sequenceNumber, rangeStart, rangeEnd);
        orderingBufferService.bufferDelivery(endpointId, delivery.getId(), sequenceNumber);

        Instant until = Instant.now().plusSeconds(rescheduleDelaySeconds);
        delivery.handBackTo(until);
        try {
            deliveryRepository.save(delivery);
        } catch (OptimisticLockingFailureException e) {
            // Someone advanced the row while we were parking it; the buffer entry is already
            // in place, so nothing is lost. Swallowed because propagating stalls the partition.
            log.warn("Delivery {} (seq={}) was updated concurrently while being buffered; "
                    + "leaving the other writer's state in place", delivery.getId(), sequenceNumber);
        }
        return until;
    }

    /**
     * Moves the cursor past this Delivery and republishes whatever it was blocking. Called for
     * every outcome that ends the obligation, terminal failure included: a cursor left behind
     * parks the endpoint at that sequence forever.
     */
    void release(Delivery delivery, boolean removeFromBuffer) {
        if (!Boolean.TRUE.equals(delivery.getOrderingEnabled()) || delivery.getSequenceNumber() == null) {
            return;
        }
        try {
            if (removeFromBuffer) {
                orderingBufferService.removeFromBuffer(delivery.getEndpointId(), delivery.getId());
            }
            orderingBufferService.markDelivered(delivery.getEndpointId(), delivery.getSequenceNumber());
            triggerBufferedDeliveries(delivery.getEndpointId());
        } catch (Exception e) {
            log.error("Failed to release ordering buffer for delivery {}: {}", delivery.getId(), e.getMessage(), e);
        }
    }

    private void triggerBufferedDeliveries(UUID endpointId) {
        List<UUID> ready = orderingBufferService.getReadyDeliveries(endpointId);
        if (ready.isEmpty()) {
            return;
        }
        for (Delivery buffered : deliveryRepository.findAllById(ready)) {
            kafkaTemplate.send(KafkaTopics.DELIVERIES_DISPATCH, endpointId.toString(),
                    DeliveryMessage.builder()
                            .deliveryId(buffered.getId())
                            .eventId(buffered.getEventId())
                            .endpointId(buffered.getEndpointId())
                            .subscriptionId(buffered.getSubscriptionId())
                            .status(buffered.getStatus().name())
                            .attemptCount(buffered.getAttemptCount())
                            .sequenceNumber(buffered.getSequenceNumber())
                            .orderingEnabled(buffered.getOrderingEnabled())
                            .build());
            log.info("Triggered buffered delivery {} (seq={}) for endpoint {}",
                    buffered.getId(), buffered.getSequenceNumber(), endpointId);
        }
    }
}

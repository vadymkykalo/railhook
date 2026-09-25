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
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Per-endpoint FIFO for Outgoing. Incoming enforces no ordering. */
@Slf4j
class OrderingGate {

    private final OrderingBufferService orderingBufferService;
    private final DeliveryRepository deliveryRepository;
    private final KafkaTemplate<String, DeliveryMessage> kafkaTemplate;
    private final TransactionTemplate transactionTemplate;
    private final Counter gapTimeoutCounter;
    private final long rescheduleDelaySeconds;

    OrderingGate(OrderingBufferService orderingBufferService,
            DeliveryRepository deliveryRepository,
            KafkaTemplate<String, DeliveryMessage> kafkaTemplate,
            TransactionTemplate transactionTemplate,
            Counter gapTimeoutCounter,
            long rescheduleDelaySeconds) {
        this.orderingBufferService = orderingBufferService;
        this.deliveryRepository = deliveryRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.transactionTemplate = transactionTemplate;
        this.gapTimeoutCounter = gapTimeoutCounter;
        this.rescheduleDelaySeconds = rescheduleDelaySeconds;
    }

    /** Null if the Delivery may proceed now. Parking ends the Claim and clears its token. */
    Instant holdUntil(Delivery delivery) {
        UUID endpointId = delivery.getEndpointId();
        long sequenceNumber = delivery.getSequenceNumber();

        if (orderingBufferService.canDeliver(endpointId, sequenceNumber)) {
            return null;
        }

        // Check the whole missing range: checking only sequenceNumber - 1 let a Delivery through
        // whenever its immediate predecessor was already terminal.
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

        // Timed from when this Delivery was first buffered; the blocking row's timestamp made the
        // timeout always true for an old backlog. The timeout is for a gap that never closes, so
        // a gap that is still closing keeps holding.
        if (orderingBufferService.isGapTimedOut(delivery.getOrderingFirstBufferedAt())
                && !gapIsClosing(endpointId, rangeStart, rangeEnd)) {
            log.warn("Gap timeout for endpoint {}, proceeding with seq={} despite outstanding range [{}, {}]",
                    endpointId, sequenceNumber, rangeStart, rangeEnd);
            gapTimeoutCounter.increment();
            return null;
        }

        return park(delivery, endpointId, sequenceNumber, rangeStart, rangeEnd);
    }

    /**
     * True if anything in the gap is being attempted now or is due within one more gap timeout.
     * The default first rung and the default gap timeout are both a minute, so without this check
     * FIFO broke on the first ordinary retry. A due Delivery that nothing dispatches keeps the gap
     * open until the stranded-PENDING escalation abandons it.
     */
    private boolean gapIsClosing(UUID endpointId, long rangeStart, long rangeEnd) {
        Instant now = Instant.now();
        Duration gapTimeout = orderingBufferService.gapTimeout();
        return deliveryRepository.countGapClosingBefore(endpointId, rangeStart, rangeEnd,
                now.minus(gapTimeout), now.plus(gapTimeout)) > 0;
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
            // The buffer entry is already in place, so nothing is lost. Propagating would stall
            // the partition.
            log.warn("Delivery {} (seq={}) was updated concurrently while being buffered; "
                    + "leaving the other writer's state in place", delivery.getId(), sequenceNumber);
        }
        return until;
    }

    /** Called for every terminal outcome, or the cursor parks the endpoint forever. */
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

    /**
     * Makes each unblocked row due before republishing it. A dispatch claims only a due row, and
     * parked rows are due a few seconds out, so these messages used to be dropped and an ordered
     * endpoint drained one Delivery per poll interval. Only unclaimed rows are touched, so this
     * cannot overtake an Attempt in flight.
     */
    private void triggerBufferedDeliveries(UUID endpointId) {
        List<UUID> ready = orderingBufferService.getReadyDeliveries(endpointId);
        if (ready.isEmpty()) {
            return;
        }
        for (Delivery buffered : deliveryRepository.findAllById(ready)) {
            Integer woken = transactionTemplate.execute(tx ->
                    deliveryRepository.scheduleIfUnclaimed(buffered.getId(), Instant.now()));
            if (woken == null || woken == 0) {
                log.debug("Released delivery {} (seq={}) is not ours to wake — another attempt holds it, "
                        + "or it is already done", buffered.getId(), buffered.getSequenceNumber());
                continue;
            }
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

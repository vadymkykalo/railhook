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

/**
 * FIFO for one endpoint: whether a Delivery's turn has come, and what to let through once it is
 * done. Only the Outgoing direction has this; the Incoming direction enforces no ordering.
 */
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
        // the timeout trivially true for any backlog older than it. Waiting it out is not enough
        // on its own — the timeout is for a gap that never closes, not one that is closing.
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
     * Is the gap still closing — is anything outstanding in it being attempted now, or due to be
     * attempted within one more gap timeout?
     *
     * <p>Waiting out the timeout says how long this Delivery has been held; it does not say
     * whether holding it is futile. The default Outgoing Ladder's first rung and the default gap
     * timeout are both a minute, so a Delivery whose first Attempt failed falls due at the very
     * moment its successors' patience runs out, and whichever the retry poll reached first won.
     * FIFO therefore disengaged on the first ordinary retry — the common case — rather than on
     * the never-closing gap the timeout exists for. The load harness's ordering probe
     * ({@code load/ordering.js}) is the black-box form of exactly that.
     *
     * <p>Only measured once the timeout has already elapsed, so the extra query is off the hot
     * path. A Delivery due but not moving — nothing is dispatching it at all — does keep the gap
     * open; that ends with the stranded-PENDING escalation, which abandons it and moves the
     * cursor past it.
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

    /**
     * Republishes what the cursor has just unblocked, each made due first.
     *
     * <p>Parking stamps a {@code next_retry_at} a few seconds out, so that the fallback poll
     * picks the Delivery up if this trigger never comes. A dispatch message claims only a row
     * that is due, so the message published here used to be dropped by its own claim and the
     * Delivery waited for that poll anyway: an ordered endpoint drained a burst one Delivery per
     * poll interval however fast its receiver answered, and the fast path existed in name only.
     *
     * <p>The write matches only a row nobody has claimed, so it cannot overtake an Attempt that
     * already holds one; a row it does not match is one no message from here could be acted on.
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

package com.webhook.platform.worker.service;

import com.webhook.platform.common.dto.IncomingForwardMessage;
import com.webhook.platform.common.enums.ForwardAttemptStatus;
import com.webhook.platform.worker.attempt.AttemptRunner;
import com.webhook.platform.worker.attempt.ForwardAttemptMetrics;
import com.webhook.platform.worker.attempt.IncomingAttemptStoreFactory;
import com.webhook.platform.worker.domain.entity.IncomingDestination;
import com.webhook.platform.worker.domain.entity.IncomingEvent;
import com.webhook.platform.worker.domain.entity.IncomingForwardAttempt;
import com.webhook.platform.worker.domain.repository.IncomingDestinationRepository;
import com.webhook.platform.worker.domain.repository.IncomingEventRepository;
import com.webhook.platform.worker.domain.repository.IncomingForwardAttemptRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The Incoming half of the pipeline: resolve the Event and Destination a Forward names, run an
 * Attempt, and hand the Forward back to the retry ladder when there is no room to run one.
 */
@Service
@Slf4j
public class IncomingForwardService {

    private final IncomingEventRepository eventRepository;
    private final IncomingDestinationRepository destinationRepository;
    private final IncomingForwardAttemptRepository attemptRepository;
    private final TransactionTemplate transactionTemplate;
    private final AttemptRunner attemptRunner;
    private final IncomingAttemptStoreFactory storeFactory;
    private final ForwardAttemptMetrics metrics;

    public IncomingForwardService(
            IncomingEventRepository eventRepository,
            IncomingDestinationRepository destinationRepository,
            IncomingForwardAttemptRepository attemptRepository,
            TransactionTemplate transactionTemplate,
            AttemptRunner attemptRunner,
            IncomingAttemptStoreFactory storeFactory,
            ForwardAttemptMetrics metrics) {
        this.eventRepository = eventRepository;
        this.destinationRepository = destinationRepository;
        this.attemptRepository = attemptRepository;
        this.transactionTemplate = transactionTemplate;
        this.attemptRunner = attemptRunner;
        this.storeFactory = storeFactory;
        this.metrics = metrics;
    }

    public void processForward(IncomingForwardMessage message) {
        UUID eventId = message.getIncomingEventId();
        UUID destinationId = message.getDestinationId();
        int attemptNumber = resolveAttemptNumber(message);

        Optional<IncomingEvent> eventOpt = eventRepository.findById(eventId);
        if (eventOpt.isEmpty()) {
            log.error("Incoming event not found: {}", eventId);
            markAttemptFailedIfExists(eventId, destinationId, message.getReplaySessionId(), attemptNumber,
                    "Incoming event not found");
            return;
        }

        Optional<IncomingDestination> destOpt = destinationRepository.findById(destinationId);
        if (destOpt.isEmpty()) {
            log.error("Incoming destination not found: {}", destinationId);
            markAttemptFailedIfExists(eventId, destinationId, message.getReplaySessionId(), attemptNumber,
                    "Incoming destination not found");
            return;
        }

        IncomingEvent event = eventOpt.get();
        IncomingDestination destination = destOpt.get();

        // Whether the Destination is still admissible belongs to the store, and URL validation to
        // the Runner, both deliberately: settling either here marks the row FAILED without
        // holding a Claim on it, which is looser than every other terminal path. Only the two
        // cases above are settled here, because a row whose Event or Destination cannot be
        // resolved at all has nothing to build a store from.
        attemptRunner.run(storeFactory.create(message, event, destination), metrics);
    }

    /**
     * Hands a Forward back to the retry ladder when the executor pool is full, so the consumer can
     * ack instead of leaving the record unacked and stalling the partition.
     *
     * <p>Both entry states are handed back the same way: a dispatch row is still PENDING and a
     * retry row is already PROCESSING. Either way {@code next_retry_at} must be set — the
     * scheduler ignores rows without one, so acking without stamping it strands the Forward.
     */
    public void rescheduleForBackpressure(IncomingForwardMessage message) {
        UUID eventId = message.getIncomingEventId();
        UUID destinationId = message.getDestinationId();
        int attemptNumber = resolveAttemptNumber(message);

        transactionTemplate.executeWithoutResult(tx -> {
            IncomingForwardAttempt attempt = findAttempt(eventId, destinationId,
                    message.getReplaySessionId(), attemptNumber);
            if (attempt == null) {
                log.debug("Forward attempt {} for eventId={}, destId={} disappeared before backpressure reschedule",
                        attemptNumber, eventId, destinationId);
                return;
            }
            if (attempt.getStatus() != ForwardAttemptStatus.PENDING
                    && attempt.getStatus() != ForwardAttemptStatus.PROCESSING) {
                log.debug("Forward attempt {} for eventId={}, destId={} is already {} — skipping backpressure reschedule",
                        attemptNumber, eventId, destinationId, attempt.getStatus());
                return;
            }
            long delaySec = ThreadLocalRandom.current().nextLong(5, 16);
            attempt.handBackTo(Instant.now().plusSeconds(delaySec));
            attemptRepository.save(attempt);
            log.warn("Executor pool full, rescheduled forward eventId={}, destId={} via retry ladder in {}s "
                    + "instead of leaving it unacked", eventId, destinationId, delaySec);
        });
    }

    private int resolveAttemptNumber(IncomingForwardMessage message) {
        return message.getAttemptCount() != null && message.getAttemptCount() > 0
                ? message.getAttemptCount()
                : 1;
    }

    private void markAttemptFailedIfExists(UUID eventId, UUID destinationId, UUID replaySessionId,
            int attemptNumber, String reason) {
        transactionTemplate.executeWithoutResult(tx -> {
            List<IncomingForwardAttempt> attempts = attemptRepository
                    .findForwardAttempts(eventId, destinationId, replaySessionId);

            IncomingForwardAttempt attempt = attempts.stream()
                    .filter(a -> a.getAttemptNumber() == attemptNumber)
                    .findFirst()
                    .orElseGet(() -> attempts.stream()
                            .filter(a -> a.getStatus() == ForwardAttemptStatus.PENDING
                                    || a.getStatus() == ForwardAttemptStatus.PROCESSING)
                            .findFirst()
                            .orElse(null));

            if (attempt == null) {
                log.warn("No attempt row found to mark failed: eventId={}, destId={}, attempt={}",
                        eventId, destinationId, attemptNumber);
                return;
            }

            attempt.failWith(reason);
            attemptRepository.save(attempt);
        });
    }

    private IncomingForwardAttempt findAttempt(UUID eventId, UUID destinationId, UUID replaySessionId,
            int attemptNumber) {
        return attemptRepository
                .findForwardAttempts(eventId, destinationId, replaySessionId)
                .stream()
                .filter(a -> a.getAttemptNumber() == attemptNumber)
                .findFirst()
                .orElse(null);
    }
}

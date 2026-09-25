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

    // Never throws: a throw means "do not ack", and under asyncAcks that stalled the partition.
    // The retry ladder and the stuck sweep own the row, not Kafka redelivery.
    public void processForward(IncomingForwardMessage message) {
        try {
            runForward(message);
        } catch (Exception e) {
            log.error("Unexpected error forwarding eventId={}, destId={}: {} — acking so the "
                            + "partition keeps moving; the retry ladder and the stuck sweep own the row",
                    message.getIncomingEventId(), message.getDestinationId(), e.getMessage(), e);
        }
    }

    private void runForward(IncomingForwardMessage message) {
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

        // Destination admissibility is the store's job and URL validation the Runner's: settling
        // either here would mark the row FAILED without holding a Claim.
        attemptRunner.run(storeFactory.create(message, event, destination), metrics);
    }

    // Hands back only the Claim this message would have taken: another copy may hold the row
    // with its POST on the wire, and taking it sent the Forward twice.
    public void rescheduleForBackpressure(IncomingForwardMessage message) {
        UUID eventId = message.getIncomingEventId();
        UUID destinationId = message.getDestinationId();
        int attemptNumber = resolveAttemptNumber(message);
        boolean fencedRetry = message.getAttemptCount() != null && message.getAttemptCount() > 0
                && !message.isReplay();
        long delaySec = ThreadLocalRandom.current().nextLong(5, 16);
        Instant retryAt = Instant.now().plusSeconds(delaySec);

        if (fencedRetry && message.getStartedAt() == null) {
            log.warn("Executor pool full for retry forward eventId={}, destId={} with no started_at; "
                    + "leaving it to the stuck sweep", eventId, destinationId);
            return;
        }
        Integer written = transactionTemplate.execute(tx -> fencedRetry
                ? attemptRepository.handBackIfStillClaimed(eventId, destinationId, attemptNumber,
                        message.getReplaySessionId(), message.getStartedAt(), retryAt)
                : attemptRepository.scheduleIfUnclaimed(eventId, destinationId, attemptNumber,
                        message.getReplaySessionId(), retryAt));
        if (written == null || written == 0) {
            log.debug("Forward attempt {} for eventId={}, destId={} is not in the state this message would claim "
                    + "(another copy holds it, or it is done), skipping backpressure reschedule",
                    attemptNumber, eventId, destinationId);
            return;
        }
        log.warn("Executor pool full, rescheduled forward eventId={}, destId={} via retry ladder in {}s "
                + "instead of leaving it unacked", eventId, destinationId, delaySec);
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
}

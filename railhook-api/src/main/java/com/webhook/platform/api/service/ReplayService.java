package com.webhook.platform.api.service;

import com.webhook.platform.api.audit.AuditAction;
import com.webhook.platform.api.audit.Auditable;

import com.webhook.platform.api.tenancy.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.domain.entity.*;
import com.webhook.platform.api.domain.enums.ReplaySessionStatus;
import com.webhook.platform.api.domain.repository.*;
import com.webhook.platform.api.dto.ReplayEstimateResponse;
import com.webhook.platform.api.dto.ReplayRequest;
import com.webhook.platform.api.dto.ReplaySessionResponse;
import com.webhook.platform.api.exception.ConflictException;
import com.webhook.platform.api.exception.NotFoundException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Service
@Slf4j
public class ReplayService {

    private static final int MAX_CONCURRENT_REPLAYS_PER_PROJECT = 2;
    private static final UUID ZERO_UUID = UUID.fromString("00000000-0000-0000-0000-000000000000");

    private final ReplaySessionRepository replaySessionRepository;
    private final EventRepository eventRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionMatchingCache subscriptionMatchingCache;
    private final EventIntake eventIntake;
    private final DeliveryRepository deliveryRepository;
    private final OutboxMessageRepository outboxMessageRepository;
    private final ProjectRepository projectRepository;
    private final ObjectMapper objectMapper;
    private final DeliveryDispatch deliveryDispatch;
    private final SequenceGeneratorService sequenceGeneratorService;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate txTemplate;

    private final Counter replayEventsProcessedCounter;
    private final Counter replayDeliveriesCreatedCounter;
    private final Counter replayErrorsCounter;
    private final Timer replayBatchTimer;

    @Value("${replay.batch-size:200}")
    private int batchSize;

    @Value("${replay.batch-delay-ms:50}")
    private long batchDelayMs;

    @Value("${replay.max-events-per-session:500000}")
    private long maxEventsPerSession;

    public ReplayService(
            ReplaySessionRepository replaySessionRepository,
            EventRepository eventRepository,
            SubscriptionRepository subscriptionRepository,
            SubscriptionMatchingCache subscriptionMatchingCache,
            EventIntake eventIntake,
            DeliveryRepository deliveryRepository,
            OutboxMessageRepository outboxMessageRepository,
            ProjectRepository projectRepository,
            ObjectMapper objectMapper,
            DeliveryDispatch deliveryDispatch,
            SequenceGeneratorService sequenceGeneratorService,
            ApplicationEventPublisher events,
            PlatformTransactionManager transactionManager,
            MeterRegistry meterRegistry) {
        this.replaySessionRepository = replaySessionRepository;
        this.eventRepository = eventRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.subscriptionMatchingCache = subscriptionMatchingCache;
        this.eventIntake = eventIntake;
        this.deliveryRepository = deliveryRepository;
        this.outboxMessageRepository = outboxMessageRepository;
        this.projectRepository = projectRepository;
        this.objectMapper = objectMapper;
        this.deliveryDispatch = deliveryDispatch;
        this.sequenceGeneratorService = sequenceGeneratorService;
        this.events = events;
        this.txTemplate = new TransactionTemplate(transactionManager);
        // Each batch commits on its own whatever the caller holds: joined to an outer transaction,
        // one failing batch marked all of it rollback-only.
        this.txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        this.replayEventsProcessedCounter = Counter.builder("replay.events.processed")
                .description("Total events processed by replay").register(meterRegistry);
        this.replayDeliveriesCreatedCounter = Counter.builder("replay.deliveries.created")
                .description("Total deliveries created by replay").register(meterRegistry);
        this.replayErrorsCounter = Counter.builder("replay.errors")
                .description("Total replay batch errors").register(meterRegistry);
        this.replayBatchTimer = Timer.builder("replay.batch.duration")
                .description("Replay batch processing time").register(meterRegistry);
    }

    // ========== Public API ==========

    public ReplayEstimateResponse estimate(UUID projectId, ReplayRequest request) {
        validateProjectOwnership(projectId);
        validateRequest(request);

        long eventCount = countEvents(projectId, request);
        List<Subscription> subs = findActiveSubscriptions(projectId, request);

        long estimatedDeliveries = eventCount * subs.size();
        String warning = null;
        if (eventCount > maxEventsPerSession) {
            warning = "Event count exceeds maximum of " + maxEventsPerSession +
                      ". Consider narrowing the time range or adding filters.";
        }

        return ReplayEstimateResponse.builder()
                .totalEvents(eventCount)
                .estimatedDeliveries(estimatedDeliveries)
                .activeSubscriptions(subs.size())
                .warning(warning)
                .build();
    }

    @Auditable(action = AuditAction.REPLAY, resourceType = "ReplaySession")
    @Transactional
    public ReplaySessionResponse create(UUID projectId, ReplayRequest request, UUID userId) {
        validateProjectOwnership(projectId);
        validateRequest(request);

        long running = replaySessionRepository.countByProjectIdAndStatusIn(
                projectId,
                List.of(ReplaySessionStatus.PENDING, ReplaySessionStatus.RUNNING, ReplaySessionStatus.ESTIMATING));
        if (running >= MAX_CONCURRENT_REPLAYS_PER_PROJECT) {
            throw new ConflictException("Maximum " + MAX_CONCURRENT_REPLAYS_PER_PROJECT +
                    " concurrent replay sessions per project. Wait for existing sessions to complete.");
        }

        long eventCount = countEvents(projectId, request);
        if (eventCount == 0) {
            throw new NotFoundException("No events found matching the specified criteria");
        }
        if (eventCount > maxEventsPerSession) {
            throw new ConflictException("Event count " + eventCount + " exceeds maximum of " +
                    maxEventsPerSession + ". Narrow the time range or add filters.");
        }

        ReplaySession session = ReplaySession.builder()
                .projectId(projectId)
                .createdBy(userId)
                .status(ReplaySessionStatus.PENDING)
                .fromDate(request.getFromDate())
                .toDate(request.getToDate())
                .eventType(request.getEventType())
                .endpointId(request.getEndpointId())
                .sourceStatus(request.getSourceStatus())
                .totalEvents((int) eventCount)
                .build();

        session = replaySessionRepository.saveAndFlush(session);
        log.info("Created replay session {} for project {} — {} events", session.getId(), projectId, eventCount);

        // Run by ReplaySessionLauncher once this transaction commits, never on this thread.
        events.publishEvent(new ReplaySessionCreated(session.getId()));

        return mapToResponse(session);
    }

    public ReplaySessionResponse get(UUID projectId, UUID sessionId) {
        validateProjectOwnership(projectId);
        ReplaySession session = replaySessionRepository.findByIdAndProjectId(sessionId, projectId)
                .orElseThrow(() -> new NotFoundException("Replay session not found"));
        return mapToResponse(session);
    }

    public Page<ReplaySessionResponse> list(UUID projectId, Pageable pageable) {
        validateProjectOwnership(projectId);
        return replaySessionRepository.findByProjectIdOrderByCreatedAtDesc(projectId, pageable)
                .map(this::mapToResponse);
    }

    @Transactional
    public ReplaySessionResponse cancel(UUID projectId, UUID sessionId) {
        validateProjectOwnership(projectId);
        ReplaySession session = replaySessionRepository.findByIdAndProjectId(sessionId, projectId)
                .orElseThrow(() -> new NotFoundException("Replay session not found"));

        if (session.getStatus() == ReplaySessionStatus.COMPLETED ||
            session.getStatus() == ReplaySessionStatus.CANCELLED ||
            session.getStatus() == ReplaySessionStatus.FAILED) {
            throw new ConflictException("Cannot cancel session in status " + session.getStatus());
        }

        int updated = replaySessionRepository.cancelSession(
                sessionId,
                ReplaySessionStatus.CANCELLING,
                List.of(ReplaySessionStatus.PENDING, ReplaySessionStatus.RUNNING, ReplaySessionStatus.ESTIMATING));

        if (updated == 0) {
            throw new ConflictException("Session already finished or cancelled");
        }

        log.info("Cancelling replay session {} for project {}", sessionId, projectId);
        return get(projectId, sessionId);
    }

    // ========== Execution ==========

    /**
     * Runs a committed session to the end, batch by batch. Called by {@link ReplaySessionLauncher}
     * on the replay executor; never throws, a failure is written to the session instead.
     */
    public void run(UUID sessionId) {
        try {
            executeReplay(sessionId);
        } catch (Exception e) {
            log.error("Replay session {} failed with unexpected error", sessionId, e);
            markFailed(sessionId, e.getMessage());
        }
    }

    private void executeReplay(UUID sessionId) {
        ReplaySession session = replaySessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalStateException("Replay session not found: " + sessionId));

        // Transition to RUNNING
        session.setStatus(ReplaySessionStatus.RUNNING);
        session.setStartedAt(Instant.now());
        replaySessionRepository.saveAndFlush(session);

        UUID projectId = session.getProjectId();
        // Where each Event goes is decided per Event, by the same code a fresh ingest runs: a
        // rule can route an Event to an endpoint no Subscription covers, so an empty Subscription
        // list is no reason to stop.
        final UUID endpointFilter = session.getEndpointId();
        final UUID sid = sessionId;

        // Cursor-based batch processing
        Instant cursorCreatedAt = session.getFromDate().minusNanos(1);
        UUID cursorId = session.getLastProcessedEventId() != null ? session.getLastProcessedEventId() : ZERO_UUID;

        // If resuming, use the last processed event's timestamp
        if (session.getLastProcessedEventId() != null) {
            Event lastEvent = eventRepository.findById(session.getLastProcessedEventId()).orElse(null);
            if (lastEvent != null) {
                cursorCreatedAt = lastEvent.getCreatedAt();
            }
        }

        int totalProcessed = session.getProcessedEvents();
        int totalDeliveries = session.getDeliveriesCreated();
        int totalErrors = session.getErrors();

        while (true) {
            // Check cancellation
            ReplaySession freshSession = replaySessionRepository.findById(sessionId).orElse(null);
            if (freshSession == null ||
                freshSession.getStatus() == ReplaySessionStatus.CANCELLING ||
                freshSession.getStatus() == ReplaySessionStatus.CANCELLED) {
                log.info("Replay session {} cancelled at event {}/{}", sessionId, totalProcessed, session.getTotalEvents());
                markCancelled(sessionId);
                return;
            }

            // Fetch next batch
            List<Event> batch = fetchBatch(projectId, session, cursorCreatedAt, cursorId);
            if (batch.isEmpty()) {
                break;
            }

            // Process batch in a new transaction
            Timer.Sample sample = Timer.start();
            final List<Event> currentBatch = batch;
            try {
                BatchResult result = txTemplate.execute(status ->
                        processBatch(currentBatch, endpointFilter, sid, projectId));
                if (result != null) {
                    totalProcessed += currentBatch.size();
                    totalDeliveries += result.deliveriesCreated;
                    totalErrors += result.errors;
                    replayEventsProcessedCounter.increment(currentBatch.size());
                    replayDeliveriesCreatedCounter.increment(result.deliveriesCreated);
                    if (result.errors > 0) {
                        replayErrorsCounter.increment(result.errors);
                    }
                }
            } catch (Exception e) {
                totalErrors += currentBatch.size();
                replayErrorsCounter.increment(currentBatch.size());
                log.error("Replay batch failed for session {}", sessionId, e);
            }
            sample.stop(replayBatchTimer);

            // Advance cursor
            Event lastEvent = batch.get(batch.size() - 1);
            cursorCreatedAt = lastEvent.getCreatedAt();
            cursorId = lastEvent.getId();

            // Checkpoint progress (every batch)
            updateProgress(sessionId, totalProcessed, totalDeliveries, totalErrors, lastEvent.getId());

            // Backpressure: pause between batches to avoid overwhelming Kafka/DB
            if (batchDelayMs > 0) {
                sleep(batchDelayMs);
            }
        }

        // Mark completed
        markCompleted(sessionId, totalProcessed, totalDeliveries, totalErrors);
        log.info("Replay session {} completed: {} events → {} deliveries ({} errors)",
                sessionId, totalProcessed, totalDeliveries, totalErrors);
    }

    private BatchResult processBatch(List<Event> events, UUID endpointFilter, UUID sessionId, UUID projectId) {
        int errors = 0;
        List<Delivery> deliveriesToSave = new ArrayList<>();

        for (Event event : events) {
            EventIntake.Decision decision;
            try {
                decision = eventIntake.decide(event);
            } catch (IllegalArgumentException e) {
                // A fresh ingest refuses an Event over the fan-out limit outright, so none of
                // its endpoints get the replay either.
                errors++;
                log.warn("Event {} not replayed: {}", event.getId(), e.getMessage());
                continue;
            }
            if (decision.dropped()) {
                continue;
            }

            for (Delivery delivery : decision.deliveries()) {
                if (endpointFilter != null && !endpointFilter.equals(delivery.getEndpointId())) {
                    continue;
                }
                try {
                    if (Boolean.TRUE.equals(delivery.getOrderingEnabled())) {
                        delivery.setSequenceNumber(sequenceGeneratorService.nextSequence(delivery.getEndpointId()));
                    }
                    // A new Delivery, not the original sent again: carrying the Event's key would
                    // hand the receiver the key it already processed, and it would drop the replay.
                    delivery.setIdempotencyKey(null);
                    delivery.setReplaySessionId(sessionId);
                    deliveriesToSave.add(delivery);
                } catch (Exception e) {
                    errors++;
                    log.warn("Failed to create delivery for event {} endpoint {}: {}",
                            event.getId(), delivery.getEndpointId(), e.getMessage());
                }
            }
        }

        // Batch save all deliveries
        List<Delivery> savedDeliveries = deliveryRepository.saveAll(deliveriesToSave);
        deliveryRepository.flush();

        // Batch create and save all outbox messages
        List<OutboxMessage> outboxMessages = new ArrayList<>();
        for (Delivery delivery : savedDeliveries) {
            try {
                outboxMessages.add(deliveryDispatch.outboxFor(delivery, projectId, DeliveryDispatch.Reason.CREATED));
            } catch (Exception e) {
                errors++;
                log.warn("Failed to create outbox message for delivery {}: {}",
                        delivery.getId(), e.getMessage());
            }
        }
        outboxMessageRepository.saveAll(outboxMessages);
        outboxMessageRepository.flush();

        return new BatchResult(outboxMessages.size(), errors);
    }

    // ========== Helpers ==========

    private List<Event> fetchBatch(UUID projectId, ReplaySession session, Instant cursorCreatedAt, UUID cursorId) {
        if (session.getEventType() != null && !session.getEventType().isBlank()) {
            return eventRepository.findByCursorForReplayWithEventType(TenantContext.require(), 
                    projectId, session.getFromDate(), session.getToDate(),
                    session.getEventType(), cursorCreatedAt, cursorId, batchSize);
        }
        return eventRepository.findByCursorForReplay(TenantContext.require(), 
                projectId, session.getFromDate(), session.getToDate(),
                cursorCreatedAt, cursorId, batchSize);
    }

    private long countEvents(UUID projectId, ReplayRequest request) {
        if (request.getEventType() != null && !request.getEventType().isBlank()) {
            return eventRepository.countForReplayWithEventType(TenantContext.require(), 
                    projectId, request.getFromDate(), request.getToDate(), request.getEventType());
        }
        return eventRepository.countForReplay(TenantContext.require(), projectId, request.getFromDate(), request.getToDate());
    }

    /**
     * For the estimate only. Matched the way intake matches, patterns included; rules are not
     * applied here, so the estimate does not know what a DROP or ROUTE will change.
     */
    private List<Subscription> findActiveSubscriptions(UUID projectId, ReplayRequest request) {
        List<Subscription> subscriptions = request.getEventType() != null && !request.getEventType().isBlank()
                ? subscriptionMatchingCache.findMatching(projectId, request.getEventType())
                : subscriptionRepository.findByProjectIdAndEnabledTrue(projectId);
        if (request.getEndpointId() == null) {
            return subscriptions;
        }
        return subscriptions.stream()
                .filter(s -> request.getEndpointId().equals(s.getEndpointId()))
                .toList();
    }

    private void updateProgress(UUID sessionId, int processed, int deliveries, int errors, UUID lastEventId) {
        replaySessionRepository.findById(sessionId).ifPresent(session -> {
            session.setProcessedEvents(processed);
            session.setDeliveriesCreated(deliveries);
            session.setErrors(errors);
            session.setLastProcessedEventId(lastEventId);
            replaySessionRepository.saveAndFlush(session);
        });
    }

    private void markCompleted(UUID sessionId, int processed, int deliveries, int errors) {
        replaySessionRepository.findById(sessionId).ifPresent(session -> {
            session.setStatus(ReplaySessionStatus.COMPLETED);
            session.setProcessedEvents(processed);
            session.setDeliveriesCreated(deliveries);
            session.setErrors(errors);
            session.setCompletedAt(Instant.now());
            replaySessionRepository.saveAndFlush(session);
        });
    }

    private void markFailed(UUID sessionId, String errorMessage) {
        replaySessionRepository.findById(sessionId).ifPresent(session -> {
            session.setStatus(ReplaySessionStatus.FAILED);
            session.setErrorMessage(errorMessage != null ? errorMessage.substring(0, Math.min(errorMessage.length(), 2000)) : "Unknown error");
            session.setCompletedAt(Instant.now());
            replaySessionRepository.saveAndFlush(session);
        });
    }

    private void markCancelled(UUID sessionId) {
        replaySessionRepository.findById(sessionId).ifPresent(session -> {
            session.setStatus(ReplaySessionStatus.CANCELLED);
            session.setCancelledAt(Instant.now());
            replaySessionRepository.saveAndFlush(session);
        });
    }

    /**
     * Turns "no such project here" into a 404. {@code Project} carries {@code @TenantId}, so this
     * lookup only sees projects inside the caller's organization: a foreign project id is
     * indistinguishable from a missing one, which is intended.
     */
    private void validateProjectOwnership(UUID projectId) {
        projectRepository.findById(projectId)
                .orElseThrow(() -> new NotFoundException("Project not found"));
    }

    private void validateRequest(ReplayRequest request) {
        if (request.getFromDate().isAfter(request.getToDate())) {
            throw new IllegalArgumentException("fromDate must be before toDate");
        }
        Duration range = Duration.between(request.getFromDate(), request.getToDate());
        if (range.toDays() > 90) {
            throw new IllegalArgumentException("Time range cannot exceed 90 days");
        }
    }

    private ReplayRequest toRequest(ReplaySession session) {
        return ReplayRequest.builder()
                .fromDate(session.getFromDate())
                .toDate(session.getToDate())
                .eventType(session.getEventType())
                .endpointId(session.getEndpointId())
                .sourceStatus(session.getSourceStatus())
                .build();
    }

    private ReplaySessionResponse mapToResponse(ReplaySession session) {
        double progressPercent = session.getTotalEvents() > 0
                ? (double) session.getProcessedEvents() / session.getTotalEvents() * 100.0
                : 0.0;

        Long durationMs = null;
        if (session.getStartedAt() != null) {
            Instant end = session.getCompletedAt() != null ? session.getCompletedAt() : Instant.now();
            durationMs = Duration.between(session.getStartedAt(), end).toMillis();
        }

        return ReplaySessionResponse.builder()
                .id(session.getId())
                .projectId(session.getProjectId())
                .createdBy(session.getCreatedBy())
                .status(session.getStatus())
                .fromDate(session.getFromDate())
                .toDate(session.getToDate())
                .eventType(session.getEventType())
                .endpointId(session.getEndpointId())
                .sourceStatus(session.getSourceStatus())
                .totalEvents(session.getTotalEvents())
                .processedEvents(session.getProcessedEvents())
                .deliveriesCreated(session.getDeliveriesCreated())
                .errors(session.getErrors())
                .progressPercent(Math.round(progressPercent * 10.0) / 10.0)
                .errorMessage(session.getErrorMessage())
                .startedAt(session.getStartedAt())
                .completedAt(session.getCompletedAt())
                .cancelledAt(session.getCancelledAt())
                .createdAt(session.getCreatedAt())
                .durationMs(durationMs)
                .build();
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    record BatchResult(int deliveriesCreated, int errors) {}
}

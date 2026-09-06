package com.webhook.platform.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.domain.entity.IncomingDestination;
import com.webhook.platform.api.domain.entity.IncomingEvent;
import com.webhook.platform.api.domain.entity.IncomingForwardAttempt;
import com.webhook.platform.api.domain.entity.IncomingSource;
import com.webhook.platform.api.domain.entity.OutboxMessage;
import com.webhook.platform.api.domain.entity.Project;
import com.webhook.platform.common.enums.ForwardAttemptStatus;
import com.webhook.platform.api.domain.repository.IncomingDestinationRepository;
import com.webhook.platform.api.domain.repository.IncomingEventRepository;
import com.webhook.platform.api.domain.repository.IncomingForwardAttemptRepository;
import com.webhook.platform.api.domain.repository.IncomingSourceRepository;
import com.webhook.platform.api.domain.repository.OutboxMessageRepository;
import com.webhook.platform.api.domain.repository.ProjectRepository;
import com.webhook.platform.api.dto.IncomingBulkReplayRequest;
import com.webhook.platform.api.dto.IncomingBulkReplayResponse;
import com.webhook.platform.api.dto.IncomingEventResponse;
import com.webhook.platform.api.dto.IncomingForwardAttemptResponse;
import com.webhook.platform.api.exception.ForbiddenException;
import com.webhook.platform.api.exception.NotFoundException;
import com.webhook.platform.api.security.AuthContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Slf4j
public class IncomingEventService {

    private static final int BULK_REPLAY_BATCH_SIZE = 100;

    private final IncomingEventRepository eventRepository;
    private final IncomingSourceRepository sourceRepository;
    private final IncomingForwardAttemptRepository forwardAttemptRepository;
    private final IncomingDestinationRepository destinationRepository;
    private final OutboxMessageRepository outboxMessageRepository;
    private final ProjectRepository projectRepository;
    private final ObjectMapper objectMapper;
    private final ForwardDispatch forwardDispatch;
    private final TransactionTemplate txTemplate;
    private final PiiMaskingService piiMaskingService;

    public IncomingEventService(
            IncomingEventRepository eventRepository,
            IncomingSourceRepository sourceRepository,
            IncomingForwardAttemptRepository forwardAttemptRepository,
            IncomingDestinationRepository destinationRepository,
            OutboxMessageRepository outboxMessageRepository,
            ProjectRepository projectRepository,
            ObjectMapper objectMapper,
            ForwardDispatch forwardDispatch,
            PlatformTransactionManager txManager,
            PiiMaskingService piiMaskingService) {
        this.eventRepository = eventRepository;
        this.sourceRepository = sourceRepository;
        this.forwardAttemptRepository = forwardAttemptRepository;
        this.destinationRepository = destinationRepository;
        this.outboxMessageRepository = outboxMessageRepository;
        this.projectRepository = projectRepository;
        this.objectMapper = objectMapper;
        this.forwardDispatch = forwardDispatch;
        this.txTemplate = new TransactionTemplate(txManager);
        this.piiMaskingService = piiMaskingService;
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

    private void validateEventAccess(IncomingEvent event, AuthContext auth) {
        IncomingSource source = sourceRepository.findById(event.getIncomingSourceId())
                .orElseThrow(() -> new NotFoundException("Incoming source not found"));
        validateProjectOwnership(source.getProjectId());
        auth.validateProjectAccess(source.getProjectId());
    }

    public Page<IncomingEventResponse> listEvents(UUID projectId, UUID sourceId, Pageable pageable) {
        validateProjectOwnership(projectId);

        Page<IncomingEvent> events;
        if (sourceId != null) {
            IncomingSource source = sourceRepository.findById(sourceId)
                    .orElseThrow(() -> new NotFoundException("Incoming source not found"));
            if (!source.getProjectId().equals(projectId)) {
                throw new ForbiddenException("Source does not belong to project");
            }
            events = eventRepository.findByIncomingSourceId(sourceId, pageable);
        } else {
            events = eventRepository.findByProjectId(projectId, pageable);
        }

        // Batch-fetch source names for the page — eliminates N+1 per-row lookup
        Map<UUID, String> sourceNames = resolveSourceNames(events.getContent());

        return events.map(event -> mapToResponse(event, sourceNames));
    }

    public IncomingEventResponse getEvent(UUID id, AuthContext auth) {
        IncomingEvent event = eventRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Incoming event not found"));
        validateEventAccess(event, auth);
        return mapToResponse(event);
    }

    public Page<IncomingForwardAttemptResponse> getEventAttempts(UUID eventId, AuthContext auth, Pageable pageable) {
        IncomingEvent event = eventRepository.findById(eventId)
                .orElseThrow(() -> new NotFoundException("Incoming event not found"));
        validateEventAccess(event, auth);

        return forwardAttemptRepository.findByIncomingEventId(eventId, pageable)
                .map(attempt -> IncomingForwardAttemptResponse.builder()
                        .id(attempt.getId())
                        .incomingEventId(attempt.getIncomingEventId())
                        .destinationId(attempt.getDestinationId())
                        .attemptNumber(attempt.getAttemptNumber())
                        .status(attempt.getStatus())
                        .startedAt(attempt.getStartedAt())
                        .finishedAt(attempt.getFinishedAt())
                        .requestHeadersJson(attempt.getRequestHeadersJson())
                        .requestBodySnippet(attempt.getRequestBodySnippet())
                        .responseCode(attempt.getResponseCode())
                        .responseHeadersJson(attempt.getResponseHeadersJson())
                        .responseBodySnippet(attempt.getResponseBodySnippet())
                        .errorMessage(attempt.getErrorMessage())
                        .nextRetryAt(attempt.getNextRetryAt())
                        .createdAt(attempt.getCreatedAt())
                        .build());
    }

    @Transactional
    public int replayEvent(UUID eventId, AuthContext auth) {
        IncomingEvent event = eventRepository.findById(eventId)
                .orElseThrow(() -> new NotFoundException("Incoming event not found"));
        validateEventAccess(event, auth);

        List<IncomingDestination> destinations = destinationRepository
                .findByIncomingSourceIdAndEnabledTrue(event.getIncomingSourceId());

        if (destinations.isEmpty()) {
            throw new IllegalStateException("No enabled destinations for this source");
        }

        UUID projectId = resolveProjectIdFromSource(event.getIncomingSourceId());
        // One session for the whole Replay, and a Ladder that starts at attempt 1 inside it.
        // Reading MAX(attempt_number) and adding one is what this used to do, and it raced the
        // live Ladder for the same number against a real unique index: whichever transaction
        // lost rolled back, and when the loser was the worker's finalise the Attempt was left
        // PROCESSING. V064 gives a Replay its own numbering so there is nothing to race for.
        UUID replaySessionId = UUID.randomUUID();
        int replayed = 0;
        for (IncomingDestination destination : destinations) {
            IncomingForwardAttempt attempt = IncomingForwardAttempt.builder()
                    .incomingEventId(eventId)
                    .destinationId(destination.getId())
                    .attemptNumber(1)
                    .replaySessionId(replaySessionId)
                    .status(ForwardAttemptStatus.PENDING)
                    .build();
            forwardAttemptRepository.save(attempt);

            try {
                outboxMessageRepository.save(forwardDispatch.outboxFor(eventId,
                        event.getIncomingSourceId(), destination.getId(), projectId,
                        1, replaySessionId, ForwardDispatch.Reason.REPLAY));
                replayed++;
            } catch (Exception e) {
                log.error("Failed to create replay outbox message: eventId={}, destId={}",
                        eventId, destination.getId(), e);
            }
        }

        log.info("Replayed incoming event {} to {} destinations", eventId, replayed);
        return replayed;
    }

    public IncomingBulkReplayResponse bulkReplay(UUID projectId, IncomingBulkReplayRequest request) {
        validateProjectOwnership(projectId);

        // Validate source belongs to project
        IncomingSource source = sourceRepository.findById(request.getSourceId())
                .orElseThrow(() -> new NotFoundException("Incoming source not found"));
        if (!source.getProjectId().equals(projectId)) {
            throw new ForbiddenException("Source does not belong to this project");
        }

        List<IncomingDestination> destinations = destinationRepository
                .findByIncomingSourceIdAndEnabledTrue(request.getSourceId());
        if (destinations.isEmpty()) {
            throw new IllegalStateException("No enabled destinations for this source");
        }

        int maxEvents = request.getMaxEvents() != null ? Math.min(request.getMaxEvents(), 5000) : 1000;

        // Resolve events to replay
        List<IncomingEvent> events;
        if (request.getEventIds() != null && !request.getEventIds().isEmpty()) {
            events = eventRepository.findAllById(request.getEventIds());
            // Filter to only events belonging to this source
            events = events.stream()
                    .filter(e -> e.getIncomingSourceId().equals(request.getSourceId()))
                    .limit(maxEvents)
                    .toList();
        } else {
            events = eventRepository.findForBulkReplay(
                    request.getSourceId(),
                    request.getFrom(),
                    request.getTo(),
                    request.getVerified(),
                    PageRequest.of(0, maxEvents));
        }

        if (events.isEmpty()) {
            return IncomingBulkReplayResponse.builder()
                    .status("bulk_replayed")
                    .sourceId(request.getSourceId())
                    .eventsReplayed(0)
                    .totalForwardAttempts(0)
                    .build();
        }

        // Process in batches to avoid long-held DB locks and connection pool exhaustion. One
        // session spans the whole bulk Replay, batches included, so re-running it stays idempotent
        // against the partial unique index V064 introduced.
        UUID replaySessionId = UUID.randomUUID();
        int totalAttempts = 0;
        for (int i = 0; i < events.size(); i += BULK_REPLAY_BATCH_SIZE) {
            List<IncomingEvent> batch = events.subList(i, Math.min(i + BULK_REPLAY_BATCH_SIZE, events.size()));
            totalAttempts += processBulkReplayBatch(batch, destinations, source.getId(), projectId,
                    replaySessionId);
        }

        log.info("Bulk replayed {} events to {} destinations ({} total attempts) for source {}",
                events.size(), destinations.size(), totalAttempts, request.getSourceId());

        return IncomingBulkReplayResponse.builder()
                .status("bulk_replayed")
                .sourceId(request.getSourceId())
                .eventsReplayed(events.size())
                .totalForwardAttempts(totalAttempts)
                .build();
    }

    private int processBulkReplayBatch(List<IncomingEvent> events, List<IncomingDestination> destinations,
                                       UUID sourceId, UUID projectId, UUID replaySessionId) {
        Integer result = txTemplate.execute(status -> {
            List<IncomingForwardAttempt> attemptsToSave = new ArrayList<>();
            List<OutboxMessage> outboxToSave = new ArrayList<>();
            int errors = 0;

            for (IncomingEvent event : events) {
                for (IncomingDestination destination : destinations) {
                    attemptsToSave.add(IncomingForwardAttempt.builder()
                            .incomingEventId(event.getId())
                            .destinationId(destination.getId())
                            .attemptNumber(1)
                            .replaySessionId(replaySessionId)
                            .status(ForwardAttemptStatus.PENDING)
                            .build());

                    try {
                        outboxToSave.add(forwardDispatch.outboxFor(event.getId(), sourceId,
                                destination.getId(), projectId, 1, replaySessionId,
                                ForwardDispatch.Reason.BULK_REPLAY));
                    } catch (Exception e) {
                        log.error("Failed to create bulk replay outbox: eventId={}, destId={}",
                                event.getId(), destination.getId(), e);
                        errors++;
                    }
                }
            }

            forwardAttemptRepository.saveAll(attemptsToSave);
            outboxMessageRepository.saveAll(outboxToSave);
            return outboxToSave.size();
        });
        return result != null ? result : 0;
    }

    private UUID resolveProjectIdFromSource(UUID sourceId) {
        return sourceRepository.findById(sourceId)
                .map(IncomingSource::getProjectId).orElse(null);
    }

    private Map<UUID, String> resolveSourceNames(List<IncomingEvent> events) {
        List<UUID> sourceIds = events.stream()
                .map(IncomingEvent::getIncomingSourceId)
                .distinct()
                .collect(Collectors.toList());
        if (sourceIds.isEmpty()) return Map.of();
        return sourceRepository.findAllById(sourceIds).stream()
                .collect(Collectors.toMap(IncomingSource::getId, IncomingSource::getName, (a, b) -> a));
    }

    /**
     * The raw body a provider posted, with the project's masking rules applied.
     *
     * <p>Incoming carries whatever a third party sent — routinely a customer record, a payment
     * or an address. The rules reached the outgoing events list and not this, so the same
     * personal data was redacted on one screen and printed verbatim on the other. Masking is
     * configured per project, and an incoming event reaches its project through its source.
     */
    private IncomingEventResponse mapToResponse(IncomingEvent event, Map<UUID, String> sourceNames) {
        UUID projectId = resolveProjectIdFromSource(event.getIncomingSourceId());
        String bodyRaw = event.getBodyRaw();
        if (bodyRaw != null && projectId != null) {
            bodyRaw = piiMaskingService.sanitizePayload(projectId, bodyRaw);
        }
        return IncomingEventResponse.builder()
                .sourceName(sourceNames.get(event.getIncomingSourceId()))
                .id(event.getId())
                .incomingSourceId(event.getIncomingSourceId())
                .requestId(event.getRequestId())
                .method(event.getMethod())
                .path(event.getPath())
                .queryParams(event.getQueryParams())
                .headersJson(event.getHeadersJson())
                .bodyRaw(bodyRaw)
                .bodySha256(event.getBodySha256())
                .contentType(event.getContentType())
                .clientIp(event.getClientIp())
                .userAgent(event.getUserAgent())
                .verified(event.getVerified())
                .verificationError(event.getVerificationError())
                .receivedAt(event.getReceivedAt())
                .build();
    }

    private IncomingEventResponse mapToResponse(IncomingEvent event) {
        String sourceName = null;
        try {
            sourceName = sourceRepository.findById(event.getIncomingSourceId())
                    .map(IncomingSource::getName).orElse(null);
        } catch (Exception ignored) {}
        IncomingEventResponse resp = mapToResponse(event, Map.of());
        resp.setSourceName(sourceName);
        return resp;
    }
}

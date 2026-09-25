package com.webhook.platform.api.service;

import com.webhook.platform.api.audit.AuditAction;
import com.webhook.platform.api.audit.Auditable;
import com.webhook.platform.api.domain.entity.IncomingDestination;
import com.webhook.platform.api.domain.entity.IncomingEvent;
import com.webhook.platform.api.domain.entity.IncomingForwardAttempt;
import com.webhook.platform.api.domain.entity.IncomingSource;
import com.webhook.platform.api.domain.repository.IncomingDestinationRepository;
import com.webhook.platform.api.domain.repository.IncomingEventRepository;
import com.webhook.platform.api.domain.repository.IncomingForwardAttemptRepository;
import com.webhook.platform.api.domain.repository.IncomingSourceRepository;
import com.webhook.platform.api.domain.repository.OutboxMessageRepository;
import com.webhook.platform.api.domain.repository.ProjectRepository;
import com.webhook.platform.api.dto.DlqStatsResponse;
import com.webhook.platform.api.dto.IncomingDlqItemResponse;
import com.webhook.platform.api.exception.NotFoundException;
import com.webhook.platform.api.tenancy.TenantContext;
import com.webhook.platform.common.enums.ForwardAttemptStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/** The DLQ for Forwards. A replay would re-send to every Destination, not just the failed one. */
@Service
@Slf4j
@RequiredArgsConstructor
public class IncomingDlqService {

    // One unbounded DELETE would hold row locks on the whole backlog for the length of the statement.
    private static final int PURGE_BATCH_SIZE = 500;

    private final IncomingForwardAttemptRepository attemptRepository;
    private final IncomingEventRepository eventRepository;
    private final IncomingSourceRepository sourceRepository;
    private final IncomingDestinationRepository destinationRepository;
    private final OutboxMessageRepository outboxMessageRepository;
    private final ProjectRepository projectRepository;
    private final ForwardDispatch forwardDispatch;

    public void validateProjectOwnership(UUID projectId) {
        projectRepository.findById(projectId)
                .orElseThrow(() -> new NotFoundException("Project not found"));
    }

    @Transactional(readOnly = true)
    public Page<IncomingDlqItemResponse> listDlqItems(UUID projectId, UUID destinationId, Pageable pageable) {
        Page<IncomingForwardAttempt> attempts = destinationId != null
                ? attemptRepository.findDlqByProjectIdAndDestinationId(projectId, destinationId, pageable)
                : attemptRepository.findDlqByProjectId(projectId, pageable);

        Map<UUID, IncomingDestination> destinations = byId(
                destinationRepository.findAllById(distinct(attempts, IncomingForwardAttempt::getDestinationId)),
                IncomingDestination::getId);
        Map<UUID, IncomingEvent> events = byId(
                eventRepository.findAllById(distinct(attempts, IncomingForwardAttempt::getIncomingEventId)),
                IncomingEvent::getId);
        Map<UUID, IncomingSource> sources = byId(
                sourceRepository.findAllById(events.values().stream()
                        .map(IncomingEvent::getIncomingSourceId).distinct().toList()),
                IncomingSource::getId);

        return attempts.map(attempt -> mapToResponse(attempt, destinations, events, sources));
    }

    @Transactional(readOnly = true)
    public IncomingDlqItemResponse getDlqItem(UUID projectId, UUID forwardAttemptId) {
        validateProjectOwnership(projectId);
        IncomingForwardAttempt attempt = attemptRepository.findById(forwardAttemptId)
                .orElseThrow(() -> new NotFoundException("Forward attempt not found"));

        if (attempt.getStatus() != ForwardAttemptStatus.DLQ) {
            throw new IllegalArgumentException("Forward is not in DLQ");
        }
        requireInProject(attempt, projectId);

        IncomingEvent event = eventRepository.findById(attempt.getIncomingEventId()).orElse(null);
        Map<UUID, IncomingEvent> events = event != null ? Map.of(event.getId(), event) : Map.of();
        Map<UUID, IncomingSource> sources = event != null
                ? sourceRepository.findById(event.getIncomingSourceId())
                        .map(s -> Map.of(s.getId(), s)).orElse(Map.of())
                : Map.<UUID, IncomingSource>of();
        Map<UUID, IncomingDestination> destinations = destinationRepository
                .findById(attempt.getDestinationId())
                .map(d -> Map.of(d.getId(), d)).orElse(Map.of());

        return mapToResponse(attempt, destinations, events, sources);
    }

    public DlqStatsResponse getDlqStats(UUID projectId) {
        return DlqStatsResponse.builder()
                .totalItems(attemptRepository.countDlqByProjectId(projectId))
                .last24Hours(attemptRepository.countDlqByProjectIdSince(
                        projectId, Instant.now().minus(24, ChronoUnit.HOURS)))
                .last7Days(attemptRepository.countDlqByProjectIdSince(
                        projectId, Instant.now().minus(7, ChronoUnit.DAYS)))
                .build();
    }

    // The Ladder length lives on the Destination, so each retry starts a new Replay session at
    // attempt 1 instead of raising maxAttempts; the abandoned Attempt leaves the DLQ as FAILED.
    @Transactional
    @Auditable(action = AuditAction.DLQ_RETRY, resourceType = "IncomingForward")
    public int retryForwards(UUID projectId, List<UUID> forwardAttemptIds) {
        validateProjectOwnership(projectId);

        List<IncomingForwardAttempt> abandoned =
                attemptRepository.findByIdInAndStatus(forwardAttemptIds, ForwardAttemptStatus.DLQ);
        int retried = 0;

        for (IncomingForwardAttempt attempt : abandoned) {
            IncomingEvent event = eventRepository.findById(attempt.getIncomingEventId()).orElse(null);
            if (event == null) {
                continue;
            }
            IncomingSource source = sourceRepository.findById(event.getIncomingSourceId()).orElse(null);
            if (source == null || !source.getProjectId().equals(projectId)) {
                continue;
            }

            // Per Forward: two rows for one event and Destination would collide on the unique index.
            UUID replaySessionId = UUID.randomUUID();

            attemptRepository.save(IncomingForwardAttempt.builder()
                    .incomingEventId(attempt.getIncomingEventId())
                    .destinationId(attempt.getDestinationId())
                    .attemptNumber(1)
                    .replaySessionId(replaySessionId)
                    .status(ForwardAttemptStatus.PENDING)
                    .build());

            outboxMessageRepository.save(forwardDispatch.outboxFor(
                    attempt.getIncomingEventId(), source.getId(), attempt.getDestinationId(),
                    projectId, 1, replaySessionId, ForwardDispatch.Reason.DLQ_RETRY));

            attempt.setStatus(ForwardAttemptStatus.FAILED);
            attemptRepository.save(attempt);

            log.info("Retrying DLQ forward: eventId={}, destId={}",
                    attempt.getIncomingEventId(), attempt.getDestinationId());
            retried++;
        }

        return retried;
    }

    @Transactional
    @Auditable(action = AuditAction.DLQ_PURGE, resourceType = "IncomingForward")
    public int purgeAllDlq(UUID projectId) {
        validateProjectOwnership(projectId);

        long total = 0;
        int deleted;
        do {
            deleted = attemptRepository.deleteDlqBatchByProjectId(
                    TenantContext.require(), projectId, PURGE_BATCH_SIZE);
            total += deleted;
        } while (deleted == PURGE_BATCH_SIZE);

        log.info("Purged {} incoming DLQ items for project: {}", total, projectId);
        return (int) total;
    }

    private void requireInProject(IncomingForwardAttempt attempt, UUID projectId) {
        IncomingEvent event = eventRepository.findById(attempt.getIncomingEventId())
                .orElseThrow(() -> new NotFoundException("Forward attempt not found"));
        IncomingSource source = sourceRepository.findById(event.getIncomingSourceId())
                .orElseThrow(() -> new NotFoundException("Forward attempt not found"));
        if (!source.getProjectId().equals(projectId)) {
            throw new NotFoundException("Forward attempt not found");
        }
    }

    private IncomingDlqItemResponse mapToResponse(IncomingForwardAttempt attempt,
            Map<UUID, IncomingDestination> destinations,
            Map<UUID, IncomingEvent> events,
            Map<UUID, IncomingSource> sources) {
        IncomingDestination destination = destinations.get(attempt.getDestinationId());
        IncomingEvent event = events.get(attempt.getIncomingEventId());
        IncomingSource source = event != null ? sources.get(event.getIncomingSourceId()) : null;

        String lastError = attempt.getErrorMessage();
        if (lastError == null && attempt.getResponseCode() != null) {
            lastError = "HTTP " + attempt.getResponseCode();
        }

        return IncomingDlqItemResponse.builder()
                .forwardAttemptId(attempt.getId())
                .incomingEventId(attempt.getIncomingEventId())
                .destinationId(attempt.getDestinationId())
                .incomingSourceId(event != null ? event.getIncomingSourceId() : null)
                .sourceName(source != null ? source.getName() : null)
                .destinationUrl(destination != null ? destination.getUrl() : null)
                .attemptNumber(attempt.getAttemptNumber())
                .maxAttempts(destination != null ? destination.getMaxAttempts() : null)
                .responseCode(attempt.getResponseCode())
                .lastError(lastError)
                .failedAt(attempt.getFinishedAt())
                .createdAt(attempt.getCreatedAt())
                .build();
    }

    private List<UUID> distinct(Page<IncomingForwardAttempt> page,
            Function<IncomingForwardAttempt, UUID> id) {
        return page.getContent().stream().map(id).distinct().toList();
    }

    private <T> Map<UUID, T> byId(Iterable<T> rows, Function<T, UUID> id) {
        return StreamSupport.stream(rows.spliterator(), false)
                .collect(Collectors.toMap(id, Function.identity(), (a, b) -> a));
    }
}

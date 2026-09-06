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

/**
 * Browse, retry and purge for Forwards the Incoming Retry Ladder gave up on — the counterpart of
 * {@link DlqService}, which only ever knew about Deliveries.
 *
 * <p>{@code DlqMonitoringService} has published {@code incoming_forward_dlq_depth} as a backlog
 * "awaiting manual retry or purge" for as long as it has existed, and until this there was
 * neither. The only recovery was {@code IncomingEventService.replayEvent}, which fans an Incoming
 * Event out to <em>every</em> enabled Destination — so recovering one failed Forward re-sent the
 * webhook to all the Destinations that had already received it.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class IncomingDlqService {

    /** Deliberately not "all of them in one statement" — see deleteDlqBatchByProjectId. */
    private static final int PURGE_BATCH_SIZE = 500;

    private final IncomingForwardAttemptRepository attemptRepository;
    private final IncomingEventRepository eventRepository;
    private final IncomingSourceRepository sourceRepository;
    private final IncomingDestinationRepository destinationRepository;
    private final OutboxMessageRepository outboxMessageRepository;
    private final ProjectRepository projectRepository;
    private final ForwardDispatch forwardDispatch;

    /**
     * Turns "no such project here" into a 404. {@code Project} carries {@code @TenantId}, so this
     * lookup only sees projects inside the caller's organization: a foreign project id is
     * indistinguishable from a missing one, which is intended.
     */
    public void validateProjectOwnership(UUID projectId) {
        projectRepository.findById(projectId)
                .orElseThrow(() -> new NotFoundException("Project not found"));
    }

    @Transactional(readOnly = true)
    public Page<IncomingDlqItemResponse> listDlqItems(UUID projectId, UUID destinationId, Pageable pageable) {
        Page<IncomingForwardAttempt> attempts = destinationId != null
                ? attemptRepository.findDlqByProjectIdAndDestinationId(projectId, destinationId, pageable)
                : attemptRepository.findDlqByProjectId(projectId, pageable);

        // Batch-loaded for the whole page: the destination URL and the source name are one query
        // each rather than two per row.
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

    /**
     * Re-forwards each abandoned Forward to the Destination that failed, and to nothing else.
     *
     * <p>Two things differ from {@link DlqService#retryDeliveries}, both because Incoming records
     * one row per Attempt rather than mutating one row per obligation:
     *
     * <p>The Outgoing retry raises {@code maxAttempts} instead of resetting {@code attemptCount},
     * so the Attempt history stays a single ascending sequence and the new Attempt cannot collide
     * in number with one already on the record. Incoming has nowhere to raise: its Ladder length
     * lives on the Destination, so continuing at N+1 would be exhausted the moment it was claimed
     * and go straight back to DLQ. It gets a new Replay session instead — a fresh Ladder starting
     * at attempt 1 inside its own numbering, which honours the same rule the Outgoing comment is
     * about: no new Attempt ever reuses a number the record already contains.
     *
     * <p>And the abandoned Attempt is moved out of DLQ rather than reused. Its own record —
     * response, error, timings — is left intact; only its status changes, because DLQ means
     * "abandoned, awaiting a human decision" and a human has now made one. Leaving it would keep
     * it in the backlog and in {@code incoming_forward_dlq_depth} forever.
     */
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

            // A session per Forward, not per call: a Ladder that has been exhausted twice leaves
            // two DLQ rows for the same (Incoming Event, Destination), and one session for the
            // batch would make the two successors collide on the partial unique index and roll
            // the whole retry back.
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

        // Batched for the same reason the Outgoing purge is: one unbounded DELETE over a large
        // backlog holds row locks across every matching Attempt for the length of the statement.
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
        return java.util.stream.StreamSupport.stream(rows.spliterator(), false)
                .collect(Collectors.toMap(id, Function.identity(), (a, b) -> a));
    }
}

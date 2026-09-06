package com.webhook.platform.api.service;

import com.webhook.platform.api.domain.entity.Delivery;
import com.webhook.platform.api.domain.entity.DeliveryAttempt;
import com.webhook.platform.api.domain.entity.Endpoint;
import com.webhook.platform.api.domain.entity.Event;
import com.webhook.platform.api.domain.entity.Project;
import com.webhook.platform.api.domain.enums.DeliveryStatus;
import com.webhook.platform.api.tenancy.TenantContext;
import java.time.Instant;
import com.webhook.platform.api.domain.repository.DeliveryAttemptRepository;
import com.webhook.platform.api.domain.repository.DeliveryRepository;
import com.webhook.platform.api.domain.repository.EndpointRepository;
import com.webhook.platform.api.domain.repository.EventRepository;
import com.webhook.platform.api.domain.repository.ProjectRepository;
import com.webhook.platform.api.domain.specification.DeliverySpecification;
import com.webhook.platform.api.dto.BulkReplayResponse;
import com.webhook.platform.api.dto.DeliveryAttemptResponse;
import com.webhook.platform.api.dto.DeliveryResponse;
import com.webhook.platform.api.dto.DryRunReplayResponse;
import org.springframework.data.jpa.domain.Specification;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.webhook.platform.api.exception.NotFoundException;
import com.webhook.platform.api.security.AuthContext;

import java.util.List;
import java.util.UUID;

@Service
@Slf4j
public class DeliveryService {

    private static final int BULK_REPLAY_MAX_LIMIT = 5000;

    private final DeliveryRepository deliveryRepository;
    private final DeliveryAttemptRepository deliveryAttemptRepository;
    private final EndpointRepository endpointRepository;
    private final EventRepository eventRepository;
    private final ProjectRepository projectRepository;
    private final ObjectMapper objectMapper;
    private final DeliveryDispatch deliveryDispatch;
    private final PiiMaskingService piiMaskingService;

    public DeliveryService(
            DeliveryRepository deliveryRepository,
            DeliveryAttemptRepository deliveryAttemptRepository,
            EndpointRepository endpointRepository,
            EventRepository eventRepository,
            ProjectRepository projectRepository,
            ObjectMapper objectMapper,
            DeliveryDispatch deliveryDispatch,
            PiiMaskingService piiMaskingService) {
        this.deliveryRepository = deliveryRepository;
        this.deliveryAttemptRepository = deliveryAttemptRepository;
        this.endpointRepository = endpointRepository;
        this.eventRepository = eventRepository;
        this.projectRepository = projectRepository;
        this.objectMapper = objectMapper;
        this.deliveryDispatch = deliveryDispatch;
        this.piiMaskingService = piiMaskingService;
    }

    /**
     * Applies the project's masking rules, tolerating a null body.
     *
     * <p>The rules used to reach the project events list, the event diff and a shared debug
     * link, and stopped there — so the screens an operator opens *because* a delivery failed
     * showed the payload the other screens had redacted. A masking feature that holds in some
     * places is worse than none, because it is trusted.
     */
    private String mask(UUID projectId, String body) {
        if (body == null || projectId == null) {
            return body;
        }
        return piiMaskingService.sanitizePayload(projectId, body);
    }

    private void validateDeliveryAccess(Delivery delivery, AuthContext auth) {
        Event event = eventRepository.findById(delivery.getEventId())
                .orElseThrow(() -> new NotFoundException("Event not found"));
        Project project = projectRepository.findById(event.getProjectId())
                .orElseThrow(() -> new NotFoundException("Project not found"));
        auth.validateProjectAccess(project.getId());
    }

    private UUID resolveProjectId(Delivery delivery) {
        return eventRepository.findById(delivery.getEventId())
                .map(Event::getProjectId).orElse(null);
    }

    public DeliveryResponse getDelivery(UUID id, AuthContext auth) {
        Delivery delivery = deliveryRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Delivery not found"));
        validateDeliveryAccess(delivery, auth);
        return DeliveryResponse.of(delivery);
    }

    public Page<DeliveryResponse> listDeliveries(UUID eventId, AuthContext auth, Pageable pageable) {
        Page<Delivery> deliveries;
        if (eventId != null) {
            Event event = eventRepository.findById(eventId)
                    .orElseThrow(() -> new NotFoundException("Event not found"));
            Project project = projectRepository.findById(event.getProjectId())
                    .orElseThrow(() -> new NotFoundException("Project not found"));
            auth.validateProjectAccess(project.getId());
            deliveries = deliveryRepository.findByEventId(eventId, pageable);
        } else {
            throw new IllegalArgumentException("eventId parameter is required");
        }
        return deliveries.map(DeliveryResponse::of);
    }

    public Page<DeliveryResponse> listDeliveriesByProject(
            UUID projectId,
            DeliveryStatus status,
            UUID endpointId,
            UUID eventId,
            String eventType,
            Instant fromDate,
            Instant toDate,
            Pageable pageable
    ) {
        UUID organizationId = TenantContext.require();
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new NotFoundException("Project not found"));

        Specification<Delivery> spec;
        if (eventId != null) {
            spec = Specification.where(DeliverySpecification.hasEventIds(List.of(eventId)));
        } else {
            spec = Specification.where(DeliverySpecification.hasProjectId(projectId))
                    .and(DeliverySpecification.hasEventTypeContaining(eventType));
        }
        spec = spec.and(DeliverySpecification.hasStatus(status))
                .and(DeliverySpecification.hasEndpointId(endpointId))
                .and(DeliverySpecification.createdAfter(fromDate))
                .and(DeliverySpecification.createdBefore(toDate));

        Page<Delivery> deliveries = deliveryRepository.findAll(spec, pageable);

        return deliveries.map(DeliveryResponse::of);
    }

    @Transactional
    public void replayDelivery(UUID deliveryId, AuthContext auth) {
        Delivery delivery = deliveryRepository.findById(deliveryId)
                .orElseThrow(() -> new NotFoundException("Delivery not found"));
        validateDeliveryAccess(delivery, auth);
        
        if (delivery.getStatus() == DeliveryStatus.SUCCESS) {
            throw new IllegalArgumentException("Cannot replay successful delivery");
        }
        
        delivery.setStatus(DeliveryStatus.PENDING);
        delivery.setAttemptCount(0);
        delivery.setNextRetryAt(null);
        delivery.setLastAttemptAt(null);
        delivery.setFailedAt(null);
        deliveryRepository.save(delivery);
        
        deliveryDispatch.announce(delivery, resolveProjectId(delivery), DeliveryDispatch.Reason.REPLAYED);
        log.info("Replayed delivery: {}", deliveryId);
    }

    public List<DeliveryAttemptResponse> getDeliveryAttempts(UUID deliveryId, AuthContext auth) {
        Delivery delivery = deliveryRepository.findById(deliveryId)
                .orElseThrow(() -> new NotFoundException("Delivery not found"));
        validateDeliveryAccess(delivery, auth);
        
        List<DeliveryAttempt> attempts = deliveryAttemptRepository
                .findByDeliveryIdOrderByAttemptNumberAsc(deliveryId);
        
        UUID projectId = resolveProjectId(delivery);
        return attempts.stream()
                .map(a -> mapAttemptToResponse(a, projectId))
                .toList();
    }

    @Transactional
    public BulkReplayResponse bulkReplayDeliveries(List<UUID> deliveryIds, DeliveryStatus statusFilter,
                                                    UUID endpointIdFilter, UUID projectIdFilter,
                                                    Integer requestedLimit, AuthContext auth) {
        int limit = Math.min(
                requestedLimit != null && requestedLimit > 0 ? requestedLimit : BULK_REPLAY_MAX_LIMIT,
                BULK_REPLAY_MAX_LIMIT);

        if (deliveryIds != null && !deliveryIds.isEmpty()) {
            return bulkReplayByIds(deliveryIds, limit, auth);
        } else if (projectIdFilter != null) {
            return bulkReplayByProject(projectIdFilter, statusFilter, endpointIdFilter, limit, auth);
        } else {
            return BulkReplayResponse.builder()
                    .totalRequested(0).replayed(0).skipped(0)
                    .totalMatched(0).hasMore(false)
                    .message("No deliveryIds or projectId provided")
                    .build();
        }
    }

    private BulkReplayResponse bulkReplayByIds(List<UUID> deliveryIds, int limit, AuthContext auth) {
        int totalRequested = deliveryIds.size();
        List<UUID> capped = deliveryIds.size() > limit ? deliveryIds.subList(0, limit) : deliveryIds;

        int replayedCount = 0;
        int skipped = 0;
        for (UUID deliveryId : capped) {
            try {
                Delivery delivery = deliveryRepository.findById(deliveryId).orElse(null);
                if (delivery == null || delivery.getStatus() == DeliveryStatus.SUCCESS) {
                    skipped++;
                    continue;
                }
                validateDeliveryAccess(delivery, auth);
                if (enqueueReplay(delivery)) {
                    replayedCount++;
                } else {
                    skipped++;
                }
            } catch (Exception e) {
                log.warn("Skipping delivery {} - access denied or invalid", deliveryId);
                skipped++;
            }
        }

        boolean hasMore = deliveryIds.size() > limit;
        log.info("Bulk replayed {} of {} deliveries (by IDs)", replayedCount, totalRequested);
        return BulkReplayResponse.builder()
                .totalRequested(totalRequested)
                .replayed(replayedCount)
                .skipped(skipped)
                .totalMatched(totalRequested)
                .hasMore(hasMore)
                .message("Bulk replay initiated for " + replayedCount + " deliveries")
                .build();
    }

    private BulkReplayResponse bulkReplayByProject(UUID projectIdFilter, DeliveryStatus statusFilter,
                                                     UUID endpointIdFilter, int limit, AuthContext auth) {
        Project project = projectRepository.findById(projectIdFilter)
                .orElseThrow(() -> new NotFoundException("Project not found"));

        auth.validateProjectAccess(project.getId());

        Specification<Delivery> spec = Specification
                .where(DeliverySpecification.hasProjectId(projectIdFilter))
                .and(DeliverySpecification.notStatus(DeliveryStatus.SUCCESS))
                .and(DeliverySpecification.hasStatus(statusFilter))
                .and(DeliverySpecification.hasEndpointId(endpointIdFilter));

        long totalMatched = deliveryRepository.count(spec);
        Page<Delivery> page = deliveryRepository.findAll(spec, PageRequest.of(0, limit));

        int replayedCount = 0;
        for (Delivery delivery : page.getContent()) {
            if (enqueueReplay(delivery, projectIdFilter)) {
                replayedCount++;
            }
        }

        boolean hasMore = totalMatched > limit;
        log.info("Bulk replayed {} of {} deliveries (by project {})", replayedCount, totalMatched, projectIdFilter);
        return BulkReplayResponse.builder()
                .totalRequested((int) Math.min(totalMatched, limit))
                .replayed(replayedCount)
                .skipped((int) Math.min(totalMatched, limit) - replayedCount)
                .totalMatched(totalMatched)
                .hasMore(hasMore)
                .message(hasMore
                        ? "Bulk replay initiated for " + replayedCount + " deliveries (" + totalMatched + " total matched, limit " + limit + ")"
                        : "Bulk replay initiated for " + replayedCount + " deliveries")
                .build();
    }

    private boolean enqueueReplay(Delivery delivery) {
        return enqueueReplay(delivery, resolveProjectId(delivery));
    }

    private boolean enqueueReplay(Delivery delivery, UUID projectId) {
        delivery.setStatus(DeliveryStatus.PENDING);
        delivery.setAttemptCount(0);
        delivery.setNextRetryAt(null);
        delivery.setLastAttemptAt(null);
        delivery.setFailedAt(null);
        deliveryRepository.save(delivery);

        try {
            deliveryDispatch.announce(delivery, projectId, DeliveryDispatch.Reason.BULK_REPLAYED);
            return true;
        } catch (Exception e) {
            log.error("Failed to create bulk replay outbox message for delivery {}", delivery.getId(), e);
            return false;
        }
    }

    private DeliveryAttemptResponse mapAttemptToResponse(DeliveryAttempt attempt, UUID projectId) {
        return DeliveryAttemptResponse.builder()
                .id(attempt.getId())
                .deliveryId(attempt.getDeliveryId())
                .attemptNumber(attempt.getAttemptNumber())
                .requestHeaders(attempt.getRequestHeaders())
                // Masked before truncation: truncating first would let the tail of a 100 KB
                // body escape the rules simply by being past the cut.
                .requestBody(truncate(mask(projectId, attempt.getRequestBody()), 100000)) // 100KB limit
                .httpStatusCode(attempt.getHttpStatusCode())
                .responseHeaders(attempt.getResponseHeaders())
                .responseBody(truncate(mask(projectId, attempt.getResponseBody()), 100000)) // 100KB limit
                .errorMessage(attempt.getErrorMessage())
                .durationMs(attempt.getDurationMs())
                .createdAt(attempt.getCreatedAt())
                .build();
    }
    
    private String truncate(String str, int maxLength) {
        if (str == null || str.length() <= maxLength) {
            return str;
        }
        return str.substring(0, maxLength) + "... (truncated at " + maxLength + " characters)";
    }

    public DryRunReplayResponse dryRunReplay(UUID deliveryId, AuthContext auth) {
        Delivery delivery = deliveryRepository.findById(deliveryId)
                .orElseThrow(() -> new NotFoundException("Delivery not found"));
        validateDeliveryAccess(delivery, auth);

        Event event = eventRepository.findById(delivery.getEventId())
                .orElseThrow(() -> new NotFoundException("Event not found"));

        Endpoint endpoint = endpointRepository.findById(delivery.getEndpointId())
                .orElseThrow(() -> new NotFoundException("Endpoint not found"));

        List<DeliveryAttempt> attempts = deliveryAttemptRepository
                .findByDeliveryIdOrderByAttemptNumberAsc(deliveryId);

        String idempotencyKey = delivery.getIdempotencyKey() != null
                ? delivery.getIdempotencyKey()
                : event.getId().toString() + "-" + delivery.getEndpointId().toString();

        String plan;
        if (delivery.getStatus() == DeliveryStatus.SUCCESS) {
            plan = "SKIP: Delivery already succeeded. Safe replay will not re-send.";
        } else if (!endpoint.getEnabled()) {
            plan = "BLOCKED: Endpoint is disabled. Enable it before replaying.";
        } else {
            plan = "WILL_SEND: POST " + endpoint.getUrl() + " with Idempotency-Key: " + idempotencyKey
                    + " (attempt " + (delivery.getAttemptCount() + 1) + "/" + delivery.getMaxAttempts() + ")";
        }

        return DryRunReplayResponse.builder()
                .deliveryId(delivery.getId())
                .eventId(event.getId())
                .endpointId(endpoint.getId())
                .endpointUrl(endpoint.getUrl())
                .eventType(event.getEventType())
                .idempotencyKey(idempotencyKey)
                .payload(mask(event.getProjectId(), event.getDecompressedPayload()))
                .previousAttemptCount(delivery.getAttemptCount())
                .maxAttempts(delivery.getMaxAttempts())
                .currentStatus(delivery.getStatus().name())
                .lastAttemptAt(delivery.getLastAttemptAt())
                .previousAttempts(attempts.stream().map(a -> DryRunReplayResponse.AttemptSummary.builder()
                        .attemptNumber(a.getAttemptNumber())
                        .httpStatusCode(a.getHttpStatusCode())
                        .errorMessage(a.getErrorMessage())
                        .durationMs(a.getDurationMs())
                        .createdAt(a.getCreatedAt())
                        .build()).toList())
                .plan(plan)
                .build();
    }

    @Transactional
    public void replayFromAttempt(UUID deliveryId, int fromAttempt, AuthContext auth) {
        Delivery delivery = deliveryRepository.findById(deliveryId)
                .orElseThrow(() -> new NotFoundException("Delivery not found"));
        validateDeliveryAccess(delivery, auth);

        if (delivery.getStatus() == DeliveryStatus.SUCCESS) {
            throw new IllegalArgumentException("Cannot replay successful delivery");
        }

        if (fromAttempt < 1 || fromAttempt > delivery.getAttemptCount()) {
            throw new IllegalArgumentException("fromAttempt must be between 1 and " + delivery.getAttemptCount());
        }

        delivery.setStatus(DeliveryStatus.PENDING);
        delivery.setAttemptCount(fromAttempt - 1);
        delivery.setNextRetryAt(null);
        delivery.setLastAttemptAt(null);
        delivery.setFailedAt(null);
        deliveryRepository.save(delivery);

        deliveryDispatch.announce(delivery, resolveProjectId(delivery),
                DeliveryDispatch.Reason.REPLAYED_FROM_STEP);
        log.info("Replayed delivery {} from attempt {}", deliveryId, fromAttempt);
    }

}

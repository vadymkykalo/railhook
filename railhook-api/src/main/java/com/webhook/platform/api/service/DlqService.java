package com.webhook.platform.api.service;

import com.webhook.platform.api.audit.AuditAction;
import com.webhook.platform.api.audit.Auditable;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.domain.entity.Delivery;
import com.webhook.platform.api.domain.entity.DeliveryAttempt;
import com.webhook.platform.api.domain.entity.Endpoint;
import com.webhook.platform.api.domain.entity.Event;
import com.webhook.platform.api.domain.entity.Project;
import com.webhook.platform.api.domain.enums.DeliveryStatus;
import com.webhook.platform.api.domain.repository.DeliveryAttemptRepository;
import com.webhook.platform.api.domain.repository.DeliveryRepository;
import com.webhook.platform.api.domain.repository.EventRepository;
import com.webhook.platform.api.domain.repository.ProjectRepository;
import com.webhook.platform.api.dto.DlqItemResponse;
import com.webhook.platform.api.dto.DlqStatsResponse;
import com.webhook.platform.api.tenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.webhook.platform.api.exception.NotFoundException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class DlqService {

    /**
     * How many more attempts a delivery retried out of the DLQ gets.
     *
     * <p>Added to the count it already has rather than replacing it: the attempt history stays
     * a single ascending sequence, which is what {@code delivery_attempts}' uniqueness on
     * {@code (delivery_id, attempt_number)} assumes and what makes "the latest attempt" a
     * well-defined thing.</p>
     */
    private static final int DLQ_RETRY_ATTEMPTS = 3;

    /** Deliberately not "all of them in one statement" — see deleteDlqBatchByProjectId. */
    private static final int PURGE_BATCH_SIZE = 500;

    private final DeliveryRepository deliveryRepository;
    private final DeliveryAttemptRepository deliveryAttemptRepository;
    private final EventRepository eventRepository;
    private final ProjectRepository projectRepository;
    private final ObjectMapper objectMapper;
    private final DeliveryDispatch deliveryDispatch;

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
    public Page<DlqItemResponse> listDlqItems(UUID projectId, UUID endpointId, Pageable pageable) {
        Page<Delivery> deliveries;
        if (endpointId != null) {
            deliveries = deliveryRepository.findDlqByProjectIdAndEndpointId(projectId, endpointId, pageable);
        } else {
            deliveries = deliveryRepository.findDlqByProjectId(projectId, pageable);
        }

        // Batch-load last delivery attempts in 1 query instead of N
        List<UUID> deliveryIds = deliveries.getContent().stream()
                .map(Delivery::getId).collect(Collectors.toList());
        Map<UUID, DeliveryAttempt> lastAttempts = Map.of();
        if (!deliveryIds.isEmpty()) {
            lastAttempts = deliveryAttemptRepository.findLatestAttemptsByDeliveryIds(TenantContext.require(), deliveryIds)
                    .stream()
                    .collect(Collectors.toMap(DeliveryAttempt::getDeliveryId, a -> a));
        }

        Map<UUID, DeliveryAttempt> finalLastAttempts = lastAttempts;
        return deliveries.map(d -> mapToResponse(d, finalLastAttempts.get(d.getId())));
    }

    @Transactional(readOnly = true)
    public DlqItemResponse getDlqItem(UUID projectId, UUID deliveryId) {
        validateProjectOwnership(projectId);
        Delivery delivery = deliveryRepository.findById(deliveryId)
                .orElseThrow(() -> new NotFoundException("Delivery not found"));
        
        if (delivery.getStatus() != DeliveryStatus.DLQ) {
            throw new IllegalArgumentException("Delivery is not in DLQ");
        }
        
        Optional<DeliveryAttempt> lastAttempt = deliveryAttemptRepository
                .findTopByDeliveryIdOrderByAttemptNumberDesc(delivery.getId());
        return mapToResponse(delivery, lastAttempt.orElse(null));
    }

    public DlqStatsResponse getDlqStats(UUID projectId) {
        long total = deliveryRepository.countDlqByProjectId(projectId);
        long last24h = deliveryRepository.countDlqByProjectIdSince(projectId, Instant.now().minus(24, ChronoUnit.HOURS));
        long last7d = deliveryRepository.countDlqByProjectIdSince(projectId, Instant.now().minus(7, ChronoUnit.DAYS));
        
        return DlqStatsResponse.builder()
                .totalItems(total)
                .last24Hours(last24h)
                .last7Days(last7d)
                .build();
    }

    @Transactional
    @Auditable(action = AuditAction.DLQ_RETRY, resourceType = "Delivery")
    public int retryDeliveries(UUID projectId, List<UUID> deliveryIds) {
        validateProjectOwnership(projectId);
        
        List<Delivery> deliveries = deliveryRepository.findByIdInAndStatus(deliveryIds, DeliveryStatus.DLQ);
        int retried = 0;
        
        for (Delivery delivery : deliveries) {
            // Verify delivery belongs to the project
            Event event = eventRepository.findById(delivery.getEventId()).orElse(null);
            if (event == null || !event.getProjectId().equals(projectId)) {
                continue;
            }
            
            // attemptCount is deliberately NOT reset to 0. The retry ladder reads it to decide
            // the next delay, and delivery_attempts is keyed on (delivery_id, attempt_number):
            // restarting the count makes the attempt this retry records collide in number with
            // one already on the record, so the attempt history of a retried delivery reads as
            // two attempt 1s and findTopByDeliveryIdOrderByAttemptNumberDesc becomes ambiguous
            // about which is the latest. Continuing the count keeps the history a sequence.
            //
            // maxAttempts is raised instead, which is what a human pressing "retry" is asking
            // for: give this delivery another go at the ladder, without pretending the
            // attempts it already made never happened.
            delivery.setStatus(DeliveryStatus.PENDING);
            delivery.setMaxAttempts(delivery.getAttemptCount() + DLQ_RETRY_ATTEMPTS);
            delivery.setNextRetryAt(null);
            delivery.setFailedAt(null);
            deliveryRepository.save(delivery);
            
            // Create outbox message for redelivery
            deliveryDispatch.announce(delivery, projectId, DeliveryDispatch.Reason.RETRY);
            
            log.info("Retrying DLQ delivery: {}", delivery.getId());
            retried++;
        }
        
        return retried;
    }

    @Transactional
    @Auditable(action = AuditAction.DLQ_PURGE, resourceType = "Delivery")
    public int purgeAllDlq(UUID projectId) {
        validateProjectOwnership(projectId);
        
        // Batched, because this used to be one unbounded DELETE. A project with a large DLQ
        // meant a single long transaction holding row locks across every matching delivery —
        // and, now that the foreign key is back (V061), cascading into delivery_attempts for
        // each one, which is where the real volume is.
        long total = 0;
        int deleted;
        do {
            deleted = deliveryRepository.deleteDlqBatchByProjectId(
                    TenantContext.require(), projectId, PURGE_BATCH_SIZE);
            total += deleted;
        } while (deleted == PURGE_BATCH_SIZE);

        log.info("Purged {} DLQ items for project: {}", total, projectId);
        return (int) total;
    }

    private DlqItemResponse mapToResponse(Delivery delivery, DeliveryAttempt lastAttempt) {
        // Use already-fetched relations from JOIN FETCH (no extra queries)
        Event event = delivery.getEvent();
        Endpoint endpoint = delivery.getEndpoint();

        String lastError = null;
        if (lastAttempt != null) {
            lastError = lastAttempt.getErrorMessage();
            if (lastError == null && lastAttempt.getHttpStatusCode() != null) {
                lastError = "HTTP " + lastAttempt.getHttpStatusCode();
            }
        }
        
        return DlqItemResponse.builder()
                .deliveryId(delivery.getId())
                .eventId(delivery.getEventId())
                .endpointId(delivery.getEndpointId())
                .subscriptionId(delivery.getSubscriptionId())
                .eventType(event != null ? event.getEventType() : null)
                .endpointUrl(endpoint != null ? endpoint.getUrl() : null)
                .attemptCount(delivery.getAttemptCount())
                .maxAttempts(delivery.getMaxAttempts())
                .lastError(lastError)
                .failedAt(delivery.getFailedAt())
                .createdAt(delivery.getCreatedAt())
                .build();
    }

}

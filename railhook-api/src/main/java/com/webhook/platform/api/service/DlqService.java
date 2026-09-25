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

    private static final int PURGE_BATCH_SIZE = 500;

    private final DeliveryRepository deliveryRepository;
    private final DeliveryAttemptRepository deliveryAttemptRepository;
    private final EventRepository eventRepository;
    private final ProjectRepository projectRepository;
    private final ObjectMapper objectMapper;
    private final DeliveryDispatch deliveryDispatch;

    // Project carries @TenantId, so a foreign project id is indistinguishable from a missing one.
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
        // Scoped to the project, so another project's delivery is refused like a missing one.
        Delivery delivery = deliveryRepository.findById(deliveryId)
                .filter(d -> eventRepository.findById(d.getEventId())
                        .map(event -> projectId.equals(event.getProjectId()))
                        .orElse(false))
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
            Event event = eventRepository.findById(delivery.getEventId()).orElse(null);
            if (event == null || !event.getProjectId().equals(projectId)) {
                continue;
            }
            
            // Another go at the ladder, without forgetting the attempts already made.
            delivery.returnToLadder(Delivery.MANUAL_RETRY_ATTEMPTS);
            deliveryRepository.save(delivery);
            
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
        
        // Batched: one unbounded DELETE locked the whole DLQ and its cascaded attempts.
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

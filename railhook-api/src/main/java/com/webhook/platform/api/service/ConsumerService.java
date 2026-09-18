package com.webhook.platform.api.service;

import com.webhook.platform.api.audit.AuditAction;
import com.webhook.platform.api.audit.Auditable;
import com.webhook.platform.api.domain.entity.Consumer;
import com.webhook.platform.api.domain.entity.Endpoint;
import com.webhook.platform.api.domain.repository.ConsumerRepository;
import com.webhook.platform.api.domain.repository.EndpointRepository;
import com.webhook.platform.api.domain.repository.PortalSessionRepository;
import com.webhook.platform.api.domain.repository.ProjectRepository;
import com.webhook.platform.api.dto.ConsumerRequest;
import com.webhook.platform.api.dto.ConsumerResponse;
import com.webhook.platform.api.exception.ConflictException;
import com.webhook.platform.api.exception.NotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The customer's side of Consumers: who they are, and which Endpoints are theirs.
 *
 * <p>Every lookup is by id <em>and</em> project, so a Consumer of another project of the same
 * organization is "not found" exactly like a missing one — the URL names the project, and an API
 * key is confined to the project in the URL.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConsumerService {

    private final ConsumerRepository consumerRepository;
    private final EndpointRepository endpointRepository;
    private final PortalSessionRepository portalSessionRepository;
    private final ProjectRepository projectRepository;
    private final Clock clock;

    private void requireProject(UUID projectId) {
        projectRepository.findById(projectId)
                .orElseThrow(() -> new NotFoundException("Project not found"));
    }

    Consumer requireConsumer(UUID projectId, UUID consumerId) {
        return consumerRepository.findByIdAndProjectId(consumerId, projectId)
                .orElseThrow(() -> new NotFoundException("Consumer not found"));
    }

    @Auditable(action = AuditAction.CREATE, resourceType = "Consumer")
    @Transactional
    public ConsumerResponse createConsumer(UUID projectId, ConsumerRequest request) {
        requireProject(projectId);
        String externalId = request.getExternalId().trim();
        if (consumerRepository.existsByProjectIdAndExternalId(projectId, externalId)) {
            throw new ConflictException("A consumer with this externalId already exists in the project");
        }
        Consumer consumer = consumerRepository.saveAndFlush(Consumer.builder()
                .projectId(projectId)
                .externalId(externalId)
                .name(nameOrExternalId(request.getName(), externalId))
                .build());
        return toResponse(consumer, 0);
    }

    public ConsumerResponse getConsumer(UUID projectId, UUID consumerId) {
        Consumer consumer = requireConsumer(projectId, consumerId);
        return toResponse(consumer, liveEndpointCounts(List.of(consumer)).getOrDefault(consumer.getId(), 0L));
    }

    /** All the project's Consumers, or the one whose externalId is given. */
    public Page<ConsumerResponse> listConsumers(UUID projectId, String externalId, Pageable pageable) {
        requireProject(projectId);
        Page<Consumer> page = externalId == null || externalId.isBlank()
                ? consumerRepository.findByProjectId(projectId, pageable)
                : consumerRepository.findByProjectIdAndExternalId(projectId, externalId.trim(), pageable);
        Map<UUID, Long> counts = liveEndpointCounts(page.getContent());
        return page.map(consumer -> toResponse(consumer, counts.getOrDefault(consumer.getId(), 0L)));
    }

    @Auditable(action = AuditAction.UPDATE, resourceType = "Consumer")
    @Transactional
    public ConsumerResponse updateConsumer(UUID projectId, UUID consumerId, ConsumerRequest request) {
        Consumer consumer = requireConsumer(projectId, consumerId);
        String externalId = request.getExternalId().trim();
        if (!Objects.equals(externalId, consumer.getExternalId())
                && consumerRepository.existsByProjectIdAndExternalId(projectId, externalId)) {
            throw new ConflictException("A consumer with this externalId already exists in the project");
        }
        consumer.setExternalId(externalId);
        if (request.getName() != null && !request.getName().isBlank()) {
            consumer.setName(request.getName().trim());
        }
        consumer = consumerRepository.saveAndFlush(consumer);
        return toResponse(consumer, liveEndpointCounts(List.of(consumer)).getOrDefault(consumer.getId(), 0L));
    }

    /**
     * Removes a Consumer: its sessions end and its Endpoints stop receiving.
     *
     * <p>The Endpoints are soft-deleted rather than handed back to the customer unassigned. A
     * Consumer is deleted because that user is gone; an Endpoint that went on receiving their
     * events with nobody left who could see it would be exactly the silent delivery the portal
     * exists to end. Their Deliveries stay, as a deleted Endpoint's always do.
     */
    @Auditable(action = AuditAction.DELETE, resourceType = "Consumer")
    @Transactional
    public void deleteConsumer(UUID projectId, UUID consumerId) {
        Consumer consumer = requireConsumer(projectId, consumerId);
        Instant now = clock.instant();
        List<Endpoint> endpoints = endpointRepository.findByConsumerIdAndDeletedAtIsNullOrderByCreatedAtAsc(consumer.getId());
        endpoints.forEach(endpoint -> endpoint.setDeletedAt(now));
        endpointRepository.saveAll(endpoints);
        portalSessionRepository.deleteByConsumerId(consumer.getId());
        consumerRepository.delete(consumer);
        log.info("Deleted consumer {} of project {} with {} endpoints", consumerId, projectId, endpoints.size());
    }

    private Map<UUID, Long> liveEndpointCounts(List<Consumer> consumers) {
        Map<UUID, Long> counts = new HashMap<>();
        if (consumers.isEmpty()) {
            return counts;
        }
        for (Object[] row : endpointRepository.countLiveByConsumerIds(consumers.stream().map(Consumer::getId).toList())) {
            counts.put((UUID) row[0], (Long) row[1]);
        }
        return counts;
    }

    private static String nameOrExternalId(String name, String externalId) {
        return name == null || name.isBlank() ? externalId : name.trim();
    }

    private static ConsumerResponse toResponse(Consumer consumer, long endpointCount) {
        return ConsumerResponse.builder()
                .id(consumer.getId())
                .projectId(consumer.getProjectId())
                .externalId(consumer.getExternalId())
                .name(consumer.getName())
                .endpointCount(endpointCount)
                .createdAt(consumer.getCreatedAt())
                .updatedAt(consumer.getUpdatedAt())
                .build();
    }
}

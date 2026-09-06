package com.webhook.platform.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.audit.AuditAction;
import com.webhook.platform.api.audit.Auditable;
import com.webhook.platform.api.domain.entity.Endpoint;
import com.webhook.platform.api.domain.entity.Project;
import com.webhook.platform.api.domain.entity.Subscription;
import com.webhook.platform.api.domain.entity.Transformation;
import com.webhook.platform.api.domain.repository.EndpointRepository;
import com.webhook.platform.api.domain.repository.ProjectRepository;
import com.webhook.platform.api.domain.repository.SubscriptionRepository;
import com.webhook.platform.api.domain.repository.TransformationRepository;
import com.webhook.platform.api.dto.SubscriptionRequest;
import com.webhook.platform.api.dto.SubscriptionResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.webhook.platform.api.exception.ConflictException;
import com.webhook.platform.api.exception.ForbiddenException;
import com.webhook.platform.api.exception.NotFoundException;
import com.webhook.platform.common.retry.RetryLadder;
import com.webhook.platform.common.retry.RetryLadderDefaults;

import java.util.List;
import java.util.Set;
import java.util.Objects;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class SubscriptionService {

    private final SubscriptionRepository subscriptionRepository;
    private final ProjectRepository projectRepository;
    private final EndpointRepository endpointRepository;
    private final TransformationRepository transformationRepository;
    private final SubscriptionMatchingCache subscriptionMatchingCache;
    private final ObjectMapper objectMapper;

    public SubscriptionService(
            SubscriptionRepository subscriptionRepository,
            ProjectRepository projectRepository,
            EndpointRepository endpointRepository,
            TransformationRepository transformationRepository,
            SubscriptionMatchingCache subscriptionMatchingCache,
            ObjectMapper objectMapper) {
        this.subscriptionRepository = subscriptionRepository;
        this.projectRepository = projectRepository;
        this.endpointRepository = endpointRepository;
        this.transformationRepository = transformationRepository;
        this.subscriptionMatchingCache = subscriptionMatchingCache;
        this.objectMapper = objectMapper;
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

    private void validateEndpointBelongsToProject(UUID endpointId, UUID projectId) {
        Endpoint endpoint = endpointRepository.findById(endpointId)
                .orElseThrow(() -> new NotFoundException("Endpoint not found"));
        if (!endpoint.getProjectId().equals(projectId)) {
            throw new ForbiddenException("Endpoint does not belong to this project");
        }
    }

    private void validateTransformationBelongsToProject(UUID transformationId, UUID projectId) {
        Transformation transformation = transformationRepository.findById(transformationId)
                .orElseThrow(() -> new NotFoundException("Transformation not found"));
        if (!transformation.getProjectId().equals(projectId)) {
            throw new ForbiddenException("Transformation does not belong to this project");
        }
    }

    @Auditable(action = AuditAction.CREATE, resourceType = "Subscription")
    @Transactional
    public SubscriptionResponse createSubscription(UUID projectId, SubscriptionRequest request) {
        validateProjectOwnership(projectId);
        validatePayloadTemplate(request.getPayloadTemplate());
        validateEndpointBelongsToProject(request.getEndpointId(), projectId);
        if (request.getTransformationId() != null) {
            validateTransformationBelongsToProject(request.getTransformationId(), projectId);
        }

        if (subscriptionRepository.existsByEndpointIdAndEventType(request.getEndpointId(), request.getEventType())) {
            throw new ConflictException("Subscription for this endpoint and event type already exists");
        }

        // Reject a malformed ladder here rather than letting the worker meet it. Before this
        // check both pipelines answered an unparseable retry_delays by logging a warning and
        // substituting a hardcoded array of their own, so a typo silently bought the customer
        // a retry policy that was neither theirs nor documented.
        RetryLadder.validate(
                request.getRetryDelays() != null ? request.getRetryDelays() : RetryLadderDefaults.OUTGOING_DELAYS,
                "retryDelays",
                request.getMaxAttempts(), "maxAttempts");

        Subscription subscription = Subscription.builder()
                .projectId(projectId)
                .endpointId(request.getEndpointId())
                .eventType(request.getEventType())
                .enabled(request.getEnabled() != null ? request.getEnabled() : true)
                .orderingEnabled(request.getOrderingEnabled() != null ? request.getOrderingEnabled() : false)
                .maxAttempts(request.getMaxAttempts() != null ? request.getMaxAttempts()
                        : RetryLadderDefaults.OUTGOING_MAX_ATTEMPTS)
                .timeoutSeconds(request.getTimeoutSeconds() != null ? request.getTimeoutSeconds() : 30)
                .retryDelays(request.getRetryDelays() != null ? request.getRetryDelays()
                        : RetryLadderDefaults.OUTGOING_DELAYS)
                .payloadTemplate(request.getPayloadTemplate())
                .customHeaders(request.getCustomHeaders())
                .transformationId(request.getTransformationId())
                .build();
        
        subscription = subscriptionRepository.saveAndFlush(subscription);
        subscriptionMatchingCache.evict(projectId);
        return mapToResponse(subscription);
    }

    public SubscriptionResponse getSubscription(UUID id) {
        Subscription subscription = subscriptionRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Subscription not found"));
        validateProjectOwnership(subscription.getProjectId());
        return mapToResponse(subscription);
    }

    public List<SubscriptionResponse> listSubscriptions(UUID projectId) {
        validateProjectOwnership(projectId);
        List<Subscription> subscriptions = subscriptionRepository.findByProjectId(projectId);

        Set<UUID> transformationIds = subscriptions.stream()
                .map(Subscription::getTransformationId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, String> transformationNames = transformationRepository.findAllById(transformationIds).stream()
                .collect(Collectors.toMap(Transformation::getId, Transformation::getName));

        return subscriptions.stream()
                .map(s -> mapToResponse(s, transformationNames.get(s.getTransformationId())))
                .collect(Collectors.toList());
    }

    @Auditable(action = AuditAction.UPDATE, resourceType = "Subscription")
    @Transactional
    public SubscriptionResponse updateSubscription(UUID id, SubscriptionRequest request) {
        Subscription subscription = subscriptionRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Subscription not found"));
        validateProjectOwnership(subscription.getProjectId());
        
        if (request.getEndpointId() != null) {
            validateEndpointBelongsToProject(request.getEndpointId(), subscription.getProjectId());
            subscription.setEndpointId(request.getEndpointId());
        }
        if (request.getEventType() != null) {
            subscription.setEventType(request.getEventType());
        }
        if (request.getEnabled() != null) {
            subscription.setEnabled(request.getEnabled());
        }
        if (request.getOrderingEnabled() != null) {
            subscription.setOrderingEnabled(request.getOrderingEnabled());
        }
        if (request.getMaxAttempts() != null) {
            RetryLadder.validate(subscription.getRetryDelays(), "retryDelays",
                    request.getMaxAttempts(), "maxAttempts");
            subscription.setMaxAttempts(request.getMaxAttempts());
        }
        if (request.getTimeoutSeconds() != null) {
            subscription.setTimeoutSeconds(request.getTimeoutSeconds());
        }
        if (request.getRetryDelays() != null) {
            RetryLadder.validate(request.getRetryDelays(), "retryDelays");
            subscription.setRetryDelays(request.getRetryDelays());
        }
        if (request.getPayloadTemplate() != null) {
            validatePayloadTemplate(request.getPayloadTemplate());
            subscription.setPayloadTemplate(request.getPayloadTemplate());
        }
        if (request.getCustomHeaders() != null) {
            subscription.setCustomHeaders(request.getCustomHeaders());
        }
        // transformationId: explicit null clears the link, non-null sets it
        if (request.getTransformationId() != null) {
            validateTransformationBelongsToProject(request.getTransformationId(), subscription.getProjectId());
            subscription.setTransformationId(request.getTransformationId());
        }
        
        subscription = subscriptionRepository.saveAndFlush(subscription);
        subscriptionMatchingCache.evict(subscription.getProjectId());
        return mapToResponse(subscription);
    }

    @Auditable(action = AuditAction.DELETE, resourceType = "Subscription")
    @Transactional
    public void deleteSubscription(UUID id) {
        Subscription subscription = subscriptionRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Subscription not found"));
        validateProjectOwnership(subscription.getProjectId());
        subscriptionRepository.deleteById(id);
        subscriptionMatchingCache.evict(subscription.getProjectId());
    }

    private void validatePayloadTemplate(String template) {
        if (template == null || template.isBlank()) {
            return;
        }
        try {
            objectMapper.readTree(template);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid payload template: not valid JSON - " + e.getMessage());
        }
    }

    private SubscriptionResponse mapToResponse(Subscription subscription) {
        String transformationName = subscription.getTransformationId() == null ? null
                : transformationRepository.findById(subscription.getTransformationId())
                        .map(Transformation::getName)
                        .orElse(null);
        return mapToResponse(subscription, transformationName);
    }

    private SubscriptionResponse mapToResponse(Subscription subscription, String transformationName) {
        return SubscriptionResponse.builder()
                .id(subscription.getId())
                .projectId(subscription.getProjectId())
                .endpointId(subscription.getEndpointId())
                .eventType(subscription.getEventType())
                .enabled(subscription.getEnabled())
                .orderingEnabled(subscription.getOrderingEnabled())
                .maxAttempts(subscription.getMaxAttempts())
                .timeoutSeconds(subscription.getTimeoutSeconds())
                .retryDelays(subscription.getRetryDelays())
                .payloadTemplate(subscription.getPayloadTemplate())
                .customHeaders(subscription.getCustomHeaders())
                .transformationId(subscription.getTransformationId())
                .transformationName(transformationName)
                .createdAt(subscription.getCreatedAt())
                .updatedAt(subscription.getUpdatedAt())
                .build();
    }
}

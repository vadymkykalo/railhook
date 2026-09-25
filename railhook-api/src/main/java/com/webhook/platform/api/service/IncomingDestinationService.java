package com.webhook.platform.api.service;

import com.webhook.platform.common.retry.RetryableStatuses;
import com.webhook.platform.common.retry.RetryLadder;
import com.webhook.platform.common.retry.RetryLadderDefaults;
import com.webhook.platform.api.audit.AuditAction;
import com.webhook.platform.api.audit.Auditable;
import com.webhook.platform.api.domain.entity.IncomingDestination;
import com.webhook.platform.api.domain.entity.IncomingSource;
import com.webhook.platform.api.domain.entity.Transformation;
import com.webhook.platform.common.enums.IncomingAuthType;
import com.webhook.platform.api.domain.repository.IncomingDestinationRepository;
import com.webhook.platform.api.domain.repository.IncomingSourceRepository;
import com.webhook.platform.api.domain.repository.TransformationRepository;
import com.webhook.platform.api.dto.IncomingDestinationRequest;
import com.webhook.platform.api.dto.IncomingDestinationResponse;
import com.webhook.platform.api.exception.ForbiddenException;
import com.webhook.platform.api.exception.NotFoundException;
import com.webhook.platform.common.security.EncryptionKeyRegistry;
import com.webhook.platform.common.security.UrlValidator;
import com.webhook.platform.common.util.CryptoUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.UUID;

@Service
@Slf4j
public class IncomingDestinationService {

    private final IncomingDestinationRepository destinationRepository;
    private final IncomingSourceRepository sourceRepository;
    private final TransformationRepository transformationRepository;
    private final EncryptionKeyRegistry encryptionKeyRegistry;
    private final boolean allowPrivateIps;
    private final List<String> allowedHosts;
    private final RetryLadderEscalationCap retryLadderEscalationCap;

    public IncomingDestinationService(
            IncomingDestinationRepository destinationRepository,
            IncomingSourceRepository sourceRepository,
            TransformationRepository transformationRepository,
            EncryptionKeyRegistry encryptionKeyRegistry,
            @Value("${webhook.url-validation.allow-private-ips:false}") boolean allowPrivateIps,
            @Value("${webhook.url-validation.allowed-hosts:}") List<String> allowedHosts,
            RetryLadderEscalationCap retryLadderEscalationCap) {
        this.destinationRepository = destinationRepository;
        this.sourceRepository = sourceRepository;
        this.transformationRepository = transformationRepository;
        this.encryptionKeyRegistry = encryptionKeyRegistry;
        this.allowPrivateIps = allowPrivateIps;
        this.allowedHosts = allowedHosts;
        this.retryLadderEscalationCap = retryLadderEscalationCap;
    }

    // Narrowed to the project, not just the tenant: an API key is confined to the project in the URL.
    private IncomingSource requireSource(UUID projectId, UUID sourceId) {
        return sourceRepository.findByIdAndProjectId(sourceId, projectId)
                .orElseThrow(() -> new NotFoundException("Incoming source not found"));
    }

    private IncomingDestination requireDestination(UUID projectId, UUID sourceId, UUID id) {
        requireSource(projectId, sourceId);
        return destinationRepository.findByIdAndIncomingSourceId(id, sourceId)
                .orElseThrow(() -> new NotFoundException("Incoming destination not found"));
    }

    // A string so "" can mean "detach". Jackson maps both absent and empty to null for a UUID field.
    private static UUID parseTransformationId(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("transformationId must be a UUID, or empty to "
                    + "detach this destination from its transformation");
        }
    }

    private void validateTransformationBelongsToProject(UUID transformationId, UUID projectId) {
        Transformation transformation = transformationRepository.findById(transformationId)
                .orElseThrow(() -> new NotFoundException("Transformation not found"));
        if (!transformation.getProjectId().equals(projectId)) {
            throw new ForbiddenException("Transformation does not belong to this project");
        }
    }

    @Auditable(action = AuditAction.CREATE, resourceType = "IncomingDestination")
    @Transactional
    public IncomingDestinationResponse createDestination(UUID projectId, UUID sourceId,
                                                         IncomingDestinationRequest request) {
        IncomingSource source = requireSource(projectId, sourceId);
        UrlValidator.validateWebhookUrl(request.getUrl(), allowPrivateIps, allowedHosts);
        UUID transformationId = parseTransformationId(request.getTransformationId());
        if (transformationId != null) {
            validateTransformationBelongsToProject(transformationId, source.getProjectId());
        }

        RetryLadder.validate(
                request.getRetryDelays() != null ? request.getRetryDelays() : RetryLadderDefaults.INCOMING_DELAYS,
                "retryDelays",
                request.getMaxAttempts(), "maxAttempts");
        RetryableStatuses.validate(
                request.getRetryableStatuses() != null
                        ? request.getRetryableStatuses() : RetryableStatuses.DEFAULT_SPEC,
                "retryableStatuses");

        IncomingDestination destination = IncomingDestination.builder()
                .incomingSourceId(sourceId)
                .url(request.getUrl())
                .authType(request.getAuthType() != null ? request.getAuthType() : IncomingAuthType.NONE)
                .customHeadersJson(request.getCustomHeadersJson())
                .enabled(request.getEnabled() != null ? request.getEnabled() : true)
                .maxAttempts(request.getMaxAttempts() != null ? request.getMaxAttempts()
                        : RetryLadderDefaults.INCOMING_MAX_ATTEMPTS)
                .timeoutSeconds(request.getTimeoutSeconds() != null ? request.getTimeoutSeconds() : 30)
                .retryDelays(request.getRetryDelays() != null ? request.getRetryDelays()
                        : RetryLadderDefaults.INCOMING_DELAYS)
                .retryableStatuses(request.getRetryableStatuses() != null
                        ? request.getRetryableStatuses() : RetryableStatuses.DEFAULT_SPEC)
                .payloadTransform(request.getPayloadTransform())
                .transformationId(transformationId)
                .build();

        if (request.getAuthConfig() != null && !request.getAuthConfig().isBlank()) {
            CryptoUtils.EncryptedData encrypted = encryptionKeyRegistry.encrypt(request.getAuthConfig());
            destination.setAuthConfigEncrypted(encrypted.getCiphertext());
            destination.setAuthConfigIv(encrypted.getIv());
            destination.setEncryptionKeyVersion(encrypted.getKeyVersion());
        }

        retryLadderEscalationCap.requireIncomingFits(destination.getRetryDelays(), destination.getMaxAttempts());
        destination = destinationRepository.saveAndFlush(destination);
        log.info("Created incoming destination: id={}, sourceId={}, url={}", destination.getId(), sourceId, request.getUrl());
        return mapToResponse(destination);
    }

    public IncomingDestinationResponse getDestination(UUID projectId, UUID sourceId, UUID id) {
        IncomingDestination destination = requireDestination(projectId, sourceId, id);
        return mapToResponse(destination);
    }

    public Page<IncomingDestinationResponse> listDestinations(UUID projectId, UUID sourceId, Pageable pageable) {
        requireSource(projectId, sourceId);
        Page<IncomingDestination> page = destinationRepository.findByIncomingSourceId(sourceId, pageable);

        Set<UUID> transformationIds = page.getContent().stream()
                .map(IncomingDestination::getTransformationId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, String> transformationNames = transformationRepository.findAllById(transformationIds).stream()
                .collect(Collectors.toMap(Transformation::getId, Transformation::getName));

        return page.map(destination ->
                mapToResponse(destination, transformationNames.get(destination.getTransformationId())));
    }

    @Auditable(action = AuditAction.UPDATE, resourceType = "IncomingDestination")
    @Transactional
    public IncomingDestinationResponse updateDestination(UUID projectId, UUID sourceId, UUID id,
                                                         IncomingDestinationRequest request) {
        IncomingSource source = requireSource(projectId, sourceId);
        IncomingDestination destination = requireDestination(projectId, sourceId, id);

        UrlValidator.validateWebhookUrl(request.getUrl(), allowPrivateIps, allowedHosts);

        destination.setUrl(request.getUrl());

        if (request.getAuthType() != null) {
            destination.setAuthType(request.getAuthType());
        }
        if (request.getAuthConfig() != null && !request.getAuthConfig().isBlank()) {
            CryptoUtils.EncryptedData encrypted = encryptionKeyRegistry.encrypt(request.getAuthConfig());
            destination.setAuthConfigEncrypted(encrypted.getCiphertext());
            destination.setAuthConfigIv(encrypted.getIv());
            destination.setEncryptionKeyVersion(encrypted.getKeyVersion());
        }
        if (request.getCustomHeadersJson() != null) {
            destination.setCustomHeadersJson(request.getCustomHeadersJson());
        }
        if (request.getEnabled() != null) {
            destination.setEnabled(request.getEnabled());
        }
        if (request.getMaxAttempts() != null) {
            RetryLadder.validate(destination.getRetryDelays(), "retryDelays",
                    request.getMaxAttempts(), "maxAttempts");
            destination.setMaxAttempts(request.getMaxAttempts());
        }
        if (request.getTimeoutSeconds() != null) {
            destination.setTimeoutSeconds(request.getTimeoutSeconds());
        }
        if (request.getRetryDelays() != null) {
            RetryLadder.validate(request.getRetryDelays(), "retryDelays");
            destination.setRetryDelays(request.getRetryDelays());
        }
        if (request.getRetryableStatuses() != null) {
            RetryableStatuses.validate(request.getRetryableStatuses(), "retryableStatuses");
            destination.setRetryableStatuses(request.getRetryableStatuses());
        }
        if (request.getPayloadTransform() != null) {
            destination.setPayloadTransform(request.getPayloadTransform().isBlank() ? null : request.getPayloadTransform());
        }
        // Blank detaches, the same rule as payloadTransform.
        if (request.getTransformationId() != null) {
            UUID requested = parseTransformationId(request.getTransformationId());
            if (requested != null) {
                validateTransformationBelongsToProject(requested, source.getProjectId());
            }
            destination.setTransformationId(requested);
        }

        retryLadderEscalationCap.requireIncomingFits(destination.getRetryDelays(), destination.getMaxAttempts());
        destination = destinationRepository.saveAndFlush(destination);
        log.info("Updated incoming destination: id={}", id);
        return mapToResponse(destination);
    }

    @Auditable(action = AuditAction.DELETE, resourceType = "IncomingDestination")
    @Transactional
    public void deleteDestination(UUID projectId, UUID sourceId, UUID id) {
        IncomingDestination destination = requireDestination(projectId, sourceId, id);
        destinationRepository.delete(destination);
        log.info("Deleted incoming destination: id={}", id);
    }

    private IncomingDestinationResponse mapToResponse(IncomingDestination destination) {
        String transformationName = destination.getTransformationId() == null ? null
                : transformationRepository.findById(destination.getTransformationId())
                        .map(Transformation::getName)
                        .orElse(null);
        return mapToResponse(destination, transformationName);
    }

    private IncomingDestinationResponse mapToResponse(IncomingDestination destination, String transformationName) {
        return IncomingDestinationResponse.builder()
                .id(destination.getId())
                .incomingSourceId(destination.getIncomingSourceId())
                .url(destination.getUrl())
                .authType(destination.getAuthType())
                .authConfigured(destination.getAuthConfigEncrypted() != null)
                .customHeadersJson(destination.getCustomHeadersJson())
                .enabled(destination.getEnabled())
                .maxAttempts(destination.getMaxAttempts())
                .timeoutSeconds(destination.getTimeoutSeconds())
                .retryDelays(destination.getRetryDelays())
                .retryableStatuses(destination.getRetryableStatuses())
                .payloadTransform(destination.getPayloadTransform())
                .failingSince(destination.getFailingSince())
                .autoDisabledAt(destination.getAutoDisabledAt())
                .autoDisabledReason(destination.getAutoDisabledReason())
                .transformationId(destination.getTransformationId())
                .transformationName(transformationName)
                .createdAt(destination.getCreatedAt())
                .updatedAt(destination.getUpdatedAt())
                .build();
    }

}

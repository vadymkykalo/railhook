package com.webhook.platform.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.webhook.platform.api.audit.AuditAction;
import com.webhook.platform.api.audit.Auditable;
import com.webhook.platform.api.domain.entity.Project;
import com.webhook.platform.api.domain.entity.Transformation;
import com.webhook.platform.api.domain.repository.IncomingDestinationRepository;
import com.webhook.platform.api.domain.repository.ProjectRepository;
import com.webhook.platform.api.domain.repository.SubscriptionRepository;
import com.webhook.platform.api.domain.repository.TransformationRepository;
import com.webhook.platform.api.dto.TransformationRequest;
import com.webhook.platform.api.dto.TransformationResponse;
import com.webhook.platform.api.exception.NotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class TransformationService {

    private final TransformationRepository transformationRepository;
    private final ProjectRepository projectRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final IncomingDestinationRepository incomingDestinationRepository;
    private final ObjectMapper objectMapper;

    private static final Pattern EXPRESSION_PATTERN = Pattern.compile("\\$\\{([^}]*)\\}");

    /**
     * Turns "no such project here" into a 404. {@code Project} carries {@code @TenantId}, so this
     * lookup only sees projects inside the caller's organization: a foreign project id is
     * indistinguishable from a missing one, which is intended.
     */
    private void validateProjectOwnership(UUID projectId) {
        projectRepository.findById(projectId)
                .orElseThrow(() -> new NotFoundException("Project not found"));
    }

    private void validateTemplate(String template) {
        if (template == null || template.isBlank()) {
            return;
        }
        try {
            objectMapper.readTree(template);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid template: not valid JSON - " + e.getMessage());
        }
        // Validate ${...} expressions — each must start with $.
        Matcher matcher = EXPRESSION_PATTERN.matcher(template);
        while (matcher.find()) {
            String expr = matcher.group(1).trim();
            if (expr.isEmpty()) {
                throw new IllegalArgumentException("Invalid template: empty expression ${} found");
            }
            if (!expr.startsWith("$")) {
                throw new IllegalArgumentException("Invalid template: expression '" + expr + "' must start with '$' (JSONPath)");
            }
            // Compiled, not merely prefix-checked. "$" plus nonsense used to pass here and
            // fail at delivery time, once per attempt, for every event — and before the worker
            // was taught to fail loudly it did not even do that: it substituted a JSON null and
            // delivered a body full of holes. The author is the only person who can fix a typo
            // in their own path, and this is the one moment they are looking at it.
            try {
                JsonPath.compile(expr);
            } catch (Exception e) {
                throw new IllegalArgumentException(
                        "Invalid template: expression '" + expr + "' is not a valid JSONPath - "
                                + e.getMessage());
            }
        }
    }

    @Auditable(action = AuditAction.CREATE, resourceType = "Transformation")
    @Transactional
    public TransformationResponse create(UUID projectId, TransformationRequest request) {
        validateProjectOwnership(projectId);
        validateTemplate(request.getTemplate());

        if (transformationRepository.existsByProjectIdAndName(projectId, request.getName())) {
            throw new IllegalArgumentException("Transformation with name '" + request.getName() + "' already exists in this project");
        }

        Transformation transformation = Transformation.builder()
                .projectId(projectId)
                .name(request.getName())
                .description(request.getDescription())
                .template(request.getTemplate())
                .enabled(request.getEnabled() != null ? request.getEnabled() : true)
                .version(1)
                .build();

        transformation = transformationRepository.saveAndFlush(transformation);
        log.info("Created transformation: id={}, project={}", transformation.getId(), projectId);
        return mapToResponse(transformation);
    }

    public TransformationResponse get(UUID id) {
        Transformation transformation = transformationRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Transformation not found"));
        validateProjectOwnership(transformation.getProjectId());
        return mapToResponse(transformation);
    }

    public List<TransformationResponse> list(UUID projectId) {
        validateProjectOwnership(projectId);
        List<Transformation> transformations = transformationRepository.findByProjectIdOrderByNameAsc(projectId);
        Set<UUID> ids = transformations.stream().map(Transformation::getId).collect(Collectors.toSet());

        Map<UUID, Long> subscriptionCounts = countsBy(subscriptionRepository.countByTransformationIds(ids));
        Map<UUID, Long> destinationCounts = countsBy(incomingDestinationRepository.countByTransformationIds(ids));

        return transformations.stream()
                .map(t -> mapToResponse(t,
                        subscriptionCounts.getOrDefault(t.getId(), 0L),
                        destinationCounts.getOrDefault(t.getId(), 0L)))
                .collect(Collectors.toList());
    }

    private static Map<UUID, Long> countsBy(List<Object[]> rows) {
        return rows.stream().collect(Collectors.toMap(row -> (UUID) row[0], row -> (Long) row[1]));
    }

    @Auditable(action = AuditAction.UPDATE, resourceType = "Transformation")
    @Transactional
    public TransformationResponse update(UUID id, TransformationRequest request) {
        Transformation transformation = transformationRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Transformation not found"));
        validateProjectOwnership(transformation.getProjectId());

        if (request.getName() != null && !request.getName().isBlank()) {
            if (transformationRepository.existsByProjectIdAndNameAndIdNot(
                    transformation.getProjectId(), request.getName(), id)) {
                throw new IllegalArgumentException("Transformation with name '" + request.getName() + "' already exists in this project");
            }
            transformation.setName(request.getName());
        }
        if (request.getDescription() != null) {
            transformation.setDescription(request.getDescription());
        }
        if (request.getTemplate() != null && !request.getTemplate().isBlank()) {
            validateTemplate(request.getTemplate());
            transformation.setTemplate(request.getTemplate());
            transformation.setVersion(transformation.getVersion() + 1);
        }
        if (request.getEnabled() != null) {
            transformation.setEnabled(request.getEnabled());
        }

        transformation = transformationRepository.saveAndFlush(transformation);
        log.info("Updated transformation: id={}, version={}", id, transformation.getVersion());
        return mapToResponse(transformation);
    }

    @Auditable(action = AuditAction.DELETE, resourceType = "Transformation")
    @Transactional
    public void delete(UUID id) {
        Transformation transformation = transformationRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Transformation not found"));
        validateProjectOwnership(transformation.getProjectId());

        long subCount = subscriptionRepository.countByTransformationId(id);
        long destCount = incomingDestinationRepository.countByTransformationId(id);
        if (subCount + destCount > 0) {
            List<String> refs = new java.util.ArrayList<>();
            if (subCount > 0) refs.add(subCount + " subscription" + (subCount > 1 ? "s" : ""));
            if (destCount > 0) refs.add(destCount + " destination" + (destCount > 1 ? "s" : ""));
            throw new IllegalStateException("Cannot delete transformation: it is referenced by " + String.join(" and ", refs));
        }

        transformationRepository.delete(transformation);
        log.info("Deleted transformation: id={}", id);
    }

    private TransformationResponse mapToResponse(Transformation transformation) {
        return mapToResponse(transformation,
                subscriptionRepository.countByTransformationId(transformation.getId()),
                incomingDestinationRepository.countByTransformationId(transformation.getId()));
    }

    private TransformationResponse mapToResponse(Transformation transformation,
            long subscriptionCount, long destinationCount) {
        return TransformationResponse.builder()
                .id(transformation.getId())
                .projectId(transformation.getProjectId())
                .name(transformation.getName())
                .description(transformation.getDescription())
                .template(transformation.getTemplate())
                .version(transformation.getVersion())
                .enabled(transformation.getEnabled())
                .subscriptionCount(subscriptionCount)
                .destinationCount(destinationCount)
                .createdAt(transformation.getCreatedAt())
                .updatedAt(transformation.getUpdatedAt())
                .build();
    }
}

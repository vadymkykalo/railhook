package com.webhook.platform.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.webhook.platform.api.audit.AuditAction;
import com.webhook.platform.api.audit.Auditable;
import com.webhook.platform.api.domain.entity.Transformation;
import com.webhook.platform.api.domain.entity.TransformationVersion;
import com.webhook.platform.api.domain.entity.User;
import com.webhook.platform.api.domain.repository.IncomingDestinationRepository;
import com.webhook.platform.api.domain.repository.ProjectRepository;
import com.webhook.platform.api.domain.repository.SubscriptionRepository;
import com.webhook.platform.api.domain.repository.TransformationRepository;
import com.webhook.platform.api.domain.repository.TransformationVersionRepository;
import com.webhook.platform.api.domain.repository.UserRepository;
import com.webhook.platform.api.dto.TransformationRequest;
import com.webhook.platform.api.dto.TransformationResponse;
import com.webhook.platform.api.dto.TransformationVersionDiffResponse;
import com.webhook.platform.api.dto.TransformationVersionResponse;
import com.webhook.platform.api.exception.ConflictException;
import com.webhook.platform.api.exception.NotFoundException;
import com.webhook.platform.common.transform.JavaScriptTransformEngine;
import com.webhook.platform.common.transform.ScriptTransformException;
import com.webhook.platform.common.transform.TransformationKind;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
public class TransformationService {

    private final TransformationRepository transformationRepository;
    private final TransformationVersionRepository transformationVersionRepository;
    private final ProjectRepository projectRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final IncomingDestinationRepository incomingDestinationRepository;
    private final UserRepository userRepository;
    private final JsonDiffCalculator jsonDiffCalculator;
    private final ObjectMapper objectMapper;
    private final JavaScriptTransformEngine scriptEngine;

    /** Templates are up to 64 KB and nothing else prunes this table. Fifty is under 3 MB. */
    private final int versionHistoryLimit;

    public TransformationService(TransformationRepository transformationRepository,
                                 TransformationVersionRepository transformationVersionRepository,
                                 ProjectRepository projectRepository,
                                 SubscriptionRepository subscriptionRepository,
                                 IncomingDestinationRepository incomingDestinationRepository,
                                 UserRepository userRepository,
                                 JsonDiffCalculator jsonDiffCalculator,
                                 ObjectMapper objectMapper,
                                 JavaScriptTransformEngine scriptEngine,
                                 @Value("${transformations.version-history-limit:50}") int versionHistoryLimit) {
        this.transformationRepository = transformationRepository;
        this.transformationVersionRepository = transformationVersionRepository;
        this.projectRepository = projectRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.incomingDestinationRepository = incomingDestinationRepository;
        this.userRepository = userRepository;
        this.jsonDiffCalculator = jsonDiffCalculator;
        this.objectMapper = objectMapper;
        this.scriptEngine = scriptEngine;
        // A zero or negative cap would trim the version currently in use.
        this.versionHistoryLimit = Math.max(1, versionHistoryLimit);
    }

    // [^{}] rather than [^}]: the latter is quadratic on repeated "${{" (CodeQL
    // java/polynomial-redos). A JSONPath expression never contains a brace.
    private static final Pattern EXPRESSION_PATTERN = Pattern.compile("\\$\\{([^{}]*)\\}");

    private void validateProjectOwnership(UUID projectId) {
        projectRepository.findById(projectId)
                .orElseThrow(() -> new NotFoundException("Project not found"));
    }

    // A broken source would fail every attempt of every event, so it is refused here, in the same sandbox.
    private void validateSource(TransformationKind kind, String source) {
        if (kind == TransformationKind.JAVASCRIPT) {
            validateScript(source);
        } else {
            validateTemplate(source);
        }
    }

    private void validateScript(String script) {
        if (script == null || script.isBlank()) {
            throw new IllegalArgumentException("Invalid script: it is empty");
        }
        try {
            scriptEngine.validate(script);
        } catch (ScriptTransformException e) {
            String where = e.line() > 0 ? " (line " + e.line() + ")" : "";
            throw new IllegalArgumentException("Invalid script" + where + ": " + e.getMessage());
        }
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
        Matcher matcher = EXPRESSION_PATTERN.matcher(template);
        while (matcher.find()) {
            String expr = matcher.group(1).trim();
            if (expr.isEmpty()) {
                throw new IllegalArgumentException("Invalid template: empty expression ${} found");
            }
            if (!expr.startsWith("$")) {
                throw new IllegalArgumentException("Invalid template: expression '" + expr + "' must start with '$' (JSONPath)");
            }
            // Compiled, not prefix-checked: "$" plus nonsense used to fail every delivery instead.
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
    public TransformationResponse create(UUID projectId, TransformationRequest request, UUID actorUserId) {
        validateProjectOwnership(projectId);
        TransformationKind kind = request.getKind() == null
                ? TransformationKind.TEMPLATE : request.getKind();
        validateSource(kind, request.getTemplate());

        if (transformationRepository.existsByProjectIdAndName(projectId, request.getName())) {
            throw new IllegalArgumentException("Transformation with name '" + request.getName() + "' already exists in this project");
        }

        Transformation transformation = Transformation.builder()
                .projectId(projectId)
                .name(request.getName())
                .description(request.getDescription())
                .template(request.getTemplate())
                .kind(kind)
                .enabled(request.getEnabled() != null ? request.getEnabled() : true)
                .version(1)
                .build();

        transformation = transformationRepository.saveAndFlush(transformation);
        publishVersion(transformation, null, actorUserId);
        log.info("Created transformation: id={}, project={}", transformation.getId(), projectId);
        return mapToResponse(transformation);
    }

    public TransformationResponse get(UUID projectId, UUID id) {
        Transformation transformation = requireTransformation(projectId, id);
        return mapToResponse(transformation);
    }

    private Transformation requireTransformation(UUID projectId, UUID id) {
        return transformationRepository.findByIdAndProjectId(id, projectId)
                .orElseThrow(() -> new NotFoundException("Transformation not found"));
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
    public TransformationResponse update(UUID projectId, UUID id, TransformationRequest request, UUID actorUserId) {
        Transformation transformation = requireTransformation(projectId, id);

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
        // A rename also sends the template, so only a real change publishes a new version.
        TransformationKind kind = request.getKind() != null
                ? request.getKind() : transformation.getKind();
        boolean templateChanged = (request.getTemplate() != null
                && !request.getTemplate().isBlank()
                && !request.getTemplate().equals(transformation.getTemplate()))
                || kind != transformation.getKind();
        if (templateChanged) {
            String source = request.getTemplate() != null && !request.getTemplate().isBlank()
                    ? request.getTemplate() : transformation.getTemplate();
            validateSource(kind, source);
            transformation.setTemplate(source);
            transformation.setKind(kind);
            transformation.setVersion(transformation.getVersion() + 1);
        }
        if (request.getEnabled() != null) {
            transformation.setEnabled(request.getEnabled());
        }

        transformation = transformationRepository.saveAndFlush(transformation);
        if (templateChanged) {
            publishVersion(transformation, null, actorUserId);
        }
        log.info("Updated transformation: id={}, version={}", id, transformation.getVersion());
        return mapToResponse(transformation);
    }

    @Auditable(action = AuditAction.DELETE, resourceType = "Transformation")
    @Transactional
    public void delete(UUID projectId, UUID id) {
        Transformation transformation = requireTransformation(projectId, id);

        long subCount = subscriptionRepository.countByTransformationId(id);
        long destCount = incomingDestinationRepository.countByTransformationId(id);
        if (subCount + destCount > 0) {
            List<String> refs = new ArrayList<>();
            if (subCount > 0) refs.add(subCount + " subscription" + (subCount > 1 ? "s" : ""));
            if (destCount > 0) refs.add(destCount + " destination" + (destCount > 1 ? "s" : ""));
            throw new ConflictException("Cannot delete transformation: it is referenced by " + String.join(" and ", refs));
        }

        // transformation_versions cascades from transformations.
        transformationRepository.delete(transformation);
        log.info("Deleted transformation: id={}", id);
    }

    @Transactional(readOnly = true)
    public List<TransformationVersionResponse> listVersions(UUID projectId, UUID id) {
        Transformation transformation = requireTransformation(projectId, id);
        List<TransformationVersion> versions =
                transformationVersionRepository.findByTransformationIdOrderByVersionDesc(id);
        Map<UUID, String> emails = resolveAuthorEmails(versions);
        return versions.stream()
                .map(v -> mapVersion(v, transformation, emails, false))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public TransformationVersionResponse getVersion(UUID projectId, UUID id, int version) {
        Transformation transformation = requireTransformation(projectId, id);
        TransformationVersion stored = requireVersion(id, version);
        return mapVersion(stored, transformation, resolveAuthorEmails(List.of(stored)), true);
    }

    @Transactional(readOnly = true)
    public TransformationVersionDiffResponse diffVersions(UUID projectId, UUID id, int left, int right) {
        requireTransformation(projectId, id);
        TransformationVersion leftVersion = requireVersion(id, left);
        TransformationVersion rightVersion = requireVersion(id, right);

        return TransformationVersionDiffResponse.builder()
                .transformationId(id)
                .leftVersion(leftVersion.getVersion())
                .rightVersion(rightVersion.getVersion())
                .leftCreatedAt(leftVersion.getCreatedAt())
                .rightCreatedAt(rightVersion.getCreatedAt())
                .leftTemplate(leftVersion.getTemplate())
                .rightTemplate(rightVersion.getTemplate())
                .leftKind(leftVersion.getKind())
                .rightKind(rightVersion.getKind())
                // A field diff makes no sense for scripts; the UI diffs the two texts instead.
                .diffs(isScript(leftVersion) || isScript(rightVersion)
                        ? List.of()
                        : jsonDiffCalculator.diff(leftVersion.getTemplate(), rightVersion.getTemplate()))
                .build();
    }

    // Republishes as the next version rather than rewinding history.
    @Auditable(action = AuditAction.RESTORE, resourceType = "Transformation")
    @Transactional
    public TransformationResponse restoreVersion(UUID projectId, UUID id, int version, UUID actorUserId) {
        Transformation transformation = requireTransformation(projectId, id);
        TransformationVersion source = requireVersion(id, version);

        if (Objects.equals(transformation.getVersion(), version)) {
            throw new IllegalArgumentException(
                    "Version " + version + " is already the current version of this transformation");
        }

        transformation.setTemplate(source.getTemplate());
        // Otherwise restored JSON could sit in a row still marked JAVASCRIPT and fail to compile.
        transformation.setKind(source.getKind());
        transformation.setVersion(transformation.getVersion() + 1);
        transformation = transformationRepository.saveAndFlush(transformation);
        publishVersion(transformation, version, actorUserId);

        log.info("Restored transformation {} to the template of version {}, published as version {}",
                id, version, transformation.getVersion());
        return mapToResponse(transformation);
    }

    private static boolean isScript(TransformationVersion version) {
        return version.getKind() == TransformationKind.JAVASCRIPT;
    }

    private TransformationVersion requireVersion(UUID transformationId, int version) {
        return transformationVersionRepository.findByTransformationIdAndVersion(transformationId, version)
                .orElseThrow(() -> new NotFoundException(
                        "Version " + version + " not found for this transformation"));
    }

    private void publishVersion(Transformation transformation, Integer restoredFromVersion, UUID actorUserId) {
        transformationVersionRepository.saveAndFlush(TransformationVersion.builder()
                .transformationId(transformation.getId())
                .version(transformation.getVersion())
                .template(transformation.getTemplate())
                .kind(transformation.getKind())
                .restoredFromVersion(restoredFromVersion)
                .createdBy(actorUserId)
                .build());

        List<Integer> published =
                transformationVersionRepository.findVersionNumbersDesc(transformation.getId());
        if (published.size() > versionHistoryLimit) {
            // A cut rather than "drop the last one", so a lowered cap takes effect on the next publish.
            Integer oldestKept = published.get(versionHistoryLimit - 1);
            List<TransformationVersion> expired = transformationVersionRepository
                    .findByTransformationIdAndVersionLessThan(transformation.getId(), oldestKept);
            transformationVersionRepository.deleteAll(expired);
            log.debug("Trimmed {} expired version(s) from transformation {}", expired.size(), transformation.getId());
        }
    }

    private Map<UUID, String> resolveAuthorEmails(List<TransformationVersion> versions) {
        Set<UUID> userIds = versions.stream()
                .map(TransformationVersion::getCreatedBy)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, User::getEmail));
    }

    private TransformationVersionResponse mapVersion(TransformationVersion version,
                                                     Transformation transformation,
                                                     Map<UUID, String> emails,
                                                     boolean includeTemplate) {
        return TransformationVersionResponse.builder()
                .id(version.getId())
                .transformationId(version.getTransformationId())
                .version(version.getVersion())
                // Fifty 64 KB templates would make the list a 3 MB response.
                .template(includeTemplate ? version.getTemplate() : null)
                .kind(version.getKind())
                .current(Objects.equals(transformation.getVersion(), version.getVersion()))
                .restoredFromVersion(version.getRestoredFromVersion())
                .createdBy(version.getCreatedBy())
                .createdByEmail(version.getCreatedBy() == null ? null : emails.get(version.getCreatedBy()))
                .createdAt(version.getCreatedAt())
                .build();
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
                .kind(transformation.getKind())
                .version(transformation.getVersion())
                .enabled(transformation.getEnabled())
                .subscriptionCount(subscriptionCount)
                .destinationCount(destinationCount)
                .createdAt(transformation.getCreatedAt())
                .updatedAt(transformation.getUpdatedAt())
                .build();
    }
}

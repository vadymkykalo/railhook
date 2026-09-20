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

    /**
     * How many published templates one transformation keeps.
     *
     * <p>Capped rather than unbounded: a template is up to 64 KB, an edit is one click, and a
     * person tuning a mapping makes dozens of them in an afternoon — history that only ever grows
     * is a slow leak in a table nothing else prunes. Capped at fifty rather than at five because
     * the cap has to be past the point where anyone would still scroll: what a rollback reaches
     * for is one of the last few, and fifty of them is under 3 MB in the worst case. The oldest
     * fall off first, and the current version can never be among them.
     */
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
        // Floored at one: a zero or negative cap would trim the version the transformation is
        // currently using, and a misconfigured number must not be able to delete live data.
        this.versionHistoryLimit = Math.max(1, versionHistoryLimit);
    }

    // `[^{}]` rather than `[^}]`: with no closing brace, the old class rescanned to the end of the
    // template from every `${`, which is quadratic on a template of repeated "${{" (CodeQL
    // java/polynomial-redos). A JSONPath expression never contains a brace.
    private static final Pattern EXPRESSION_PATTERN = Pattern.compile("\\$\\{([^{}]*)\\}");

    /**
     * Turns "no such project here" into a 404. {@code Project} carries {@code @TenantId}, so this
     * lookup only sees projects inside the caller's organization: a foreign project id is
     * indistinguishable from a missing one, which is intended.
     */
    private void validateProjectOwnership(UUID projectId) {
        projectRepository.findById(projectId)
                .orElseThrow(() -> new NotFoundException("Project not found"));
    }

    /**
     * Refuses a transformation the author cannot have meant, at the one moment they are looking
     * at it.
     *
     * <p>Both languages are checked here and for the same reason: whatever is wrong is wrong once
     * per attempt, for every event, forever, and the author is the only person who can fix it. A
     * script is compiled and its {@code handler} is looked for; its top level runs, inside the
     * same sandbox and under the same limits a real Delivery gets, so a script that loops while
     * defining itself is refused here rather than on a worker.
     */
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

    /** Another project's transformation is "not found", like a missing one - the URL names the project. */
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
        // The template is required by the request DTO, so the edit form sends the whole thing back
        // whether or not it was touched: renaming a transformation and rewriting its mapping arrive
        // here as the same call. The counter used to go up for both, which — now that the counter
        // has a history behind it — would fill that history with identical entries nobody made.
        //
        // The language counts as part of the template for all of this. The same text validated
        // as a script and as a template gives different answers, and an edit that only switches
        // the language is as much a new published version as one that rewrites the text.
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

        // The history goes with it: transformation_versions cascades from transformations.
        transformationRepository.delete(transformation);
        log.info("Deleted transformation: id={}", id);
    }

    // ── Version history ──────────────────────────────────────────────

    /** Every template this transformation has published, newest first. */
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

    /** One published template, whole. */
    @Transactional(readOnly = true)
    public TransformationVersionResponse getVersion(UUID projectId, UUID id, int version) {
        Transformation transformation = requireTransformation(projectId, id);
        TransformationVersion stored = requireVersion(id, version);
        return mapVersion(stored, transformation, resolveAuthorEmails(List.of(stored)), true);
    }

    /** What changed between two published templates. */
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
                // A field-by-field diff is a thing you can do to two JSON documents and not to
                // two scripts. Rather than hand back nonsense — every line of a script reads as
                // one unparseable "field" — a script version carries no field diff and the UI
                // diffs the two texts, which is what a person reading a script wants anyway.
                .diffs(isScript(leftVersion) || isScript(rightVersion)
                        ? List.of()
                        : jsonDiffCalculator.diff(leftVersion.getTemplate(), rightVersion.getTemplate()))
                .build();
    }

    /**
     * Puts an earlier template back by publishing it again.
     *
     * <p>The history is not rewound: the versions published after the one being restored stay
     * exactly where they are, and the restored template becomes the next version, marked with the
     * one it came from. Rewinding — deleting the versions after it — would make the record of what
     * was live at any past moment disagree with what actually was.
     */
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
        // The language goes back with the text. Without this, restoring a template published
        // before the transformation was rewritten as a script would put JSON back into a row
        // still marked JAVASCRIPT — and the next delivery would fail to compile it.
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

    /**
     * Writes the transformation's current template into its history, then trims the history back
     * to {@link #versionHistoryLimit}. Called only where the template actually changed, so a row
     * here always stands for an edit somebody made.
     */
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
            // The oldest version still kept. Expressed as a cut rather than as "drop the last
            // one", because a cap lowered in configuration has to bring the history down to the
            // new number on the next publish rather than one row per edit forever.
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
                // The list is an index: a project with fifty 64 KB templates in one history would
                // otherwise be a 3 MB response nobody reads.
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

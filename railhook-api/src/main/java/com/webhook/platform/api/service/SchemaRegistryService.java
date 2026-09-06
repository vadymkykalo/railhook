package com.webhook.platform.api.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.audit.AuditAction;
import com.webhook.platform.api.audit.Auditable;
import com.webhook.platform.api.domain.entity.*;
import com.webhook.platform.api.domain.enums.CompatibilityMode;
import com.webhook.platform.api.domain.enums.SchemaStatus;
import com.webhook.platform.api.domain.repository.*;
import com.webhook.platform.api.dto.*;
import com.webhook.platform.api.exception.NotFoundException;
import com.webhook.platform.common.util.JsonSchemaUtils;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Slf4j
public class SchemaRegistryService {

    private final EventTypeCatalogRepository catalogRepository;
    private final EventSchemaVersionRepository versionRepository;
    private final SchemaChangeRepository changeRepository;
    private final ProjectRepository projectRepository;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public SchemaRegistryService(
            EventTypeCatalogRepository catalogRepository,
            EventSchemaVersionRepository versionRepository,
            SchemaChangeRepository changeRepository,
            ProjectRepository projectRepository,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry) {
        this.catalogRepository = catalogRepository;
        this.versionRepository = versionRepository;
        this.changeRepository = changeRepository;
        this.projectRepository = projectRepository;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    // ── Event Type Catalog ──

    @Auditable(action = AuditAction.CREATE, resourceType = "EventType")
    @Transactional
    public EventTypeCatalogResponse createEventType(UUID projectId, EventTypeCatalogRequest request) {
        validateProjectOwnership(projectId);

        if (catalogRepository.existsByProjectIdAndName(projectId, request.getName())) {
            throw new IllegalArgumentException("Event type '" + request.getName() + "' already exists in this project");
        }

        EventTypeCatalog entity = EventTypeCatalog.builder()
                .projectId(projectId)
                .name(request.getName())
                .description(request.getDescription())
                .build();

        entity = catalogRepository.saveAndFlush(entity);
        log.info("Created event type '{}' in project {}", request.getName(), projectId);
        return mapCatalogResponse(entity);
    }

    public List<EventTypeCatalogResponse> listEventTypes(UUID projectId) {
        validateProjectOwnership(projectId);
        return catalogRepository.findByProjectIdOrderByNameAsc(projectId).stream()
                .map(this::mapCatalogResponse)
                .collect(Collectors.toList());
    }

    public EventTypeCatalogResponse getEventType(UUID eventTypeId) {
        EventTypeCatalog entity = catalogRepository.findById(eventTypeId)
                .orElseThrow(() -> new NotFoundException("Event type not found"));
        validateProjectOwnership(entity.getProjectId());
        return mapCatalogResponse(entity);
    }

    @Auditable(action = AuditAction.UPDATE, resourceType = "EventType")
    @Transactional
    public EventTypeCatalogResponse updateEventType(UUID eventTypeId, EventTypeCatalogRequest request) {
        EventTypeCatalog entity = catalogRepository.findById(eventTypeId)
                .orElseThrow(() -> new NotFoundException("Event type not found"));
        validateProjectOwnership(entity.getProjectId());

        if (request.getDescription() != null) {
            entity.setDescription(request.getDescription());
        }
        entity = catalogRepository.saveAndFlush(entity);
        return mapCatalogResponse(entity);
    }

    @Auditable(action = AuditAction.DELETE, resourceType = "EventType")
    @Transactional
    public void deleteEventType(UUID eventTypeId) {
        EventTypeCatalog entity = catalogRepository.findById(eventTypeId)
                .orElseThrow(() -> new NotFoundException("Event type not found"));
        validateProjectOwnership(entity.getProjectId());
        catalogRepository.delete(entity);
        log.info("Deleted event type '{}'", entity.getName());
    }

    // ── Schema Versions ──

    @Auditable(action = AuditAction.CREATE, resourceType = "SchemaVersion")
    @Transactional
    public EventSchemaVersionResponse createSchemaVersion(UUID eventTypeId, EventSchemaVersionRequest request,
                                                          UUID userId) {
        EventTypeCatalog eventType = catalogRepository.findById(eventTypeId)
                .orElseThrow(() -> new NotFoundException("Event type not found"));
        validateProjectOwnership(eventType.getProjectId());

        // Validate the schema JSON is valid
        try {
            objectMapper.readTree(request.getSchemaJson());
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid JSON Schema: " + e.getMessage());
        }

        // Compute fingerprint
        String fp;
        try {
            fp = JsonSchemaUtils.fingerprint(request.getSchemaJson());
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to compute schema fingerprint: " + e.getMessage());
        }

        // Check for duplicate schema
        Optional<EventSchemaVersion> existing = versionRepository.findByEventTypeIdAndFingerprint(eventTypeId, fp);
        if (existing.isPresent()) {
            log.info("Schema with fingerprint {} already exists as version {}", fp, existing.get().getVersion());
            return mapVersionResponse(existing.get());
        }

        int nextVersion = versionRepository.findMaxVersionByEventTypeId(eventTypeId) + 1;

        Optional<EventSchemaVersion> previous = nextVersion > 1
                ? versionRepository.findByEventTypeIdAndVersion(eventTypeId, nextVersion - 1)
                : Optional.empty();

        // Omitted means "the promise the previous version made", not "no promise": a project that
        // asked for BACKWARD once should not lose it the first time somebody posts a schema
        // without repeating the field.
        CompatibilityMode mode = request.getCompatibilityMode() != null
                ? request.getCompatibilityMode()
                : previous.map(EventSchemaVersion::getCompatibilityMode).orElse(CompatibilityMode.NONE);

        previous.ifPresent(prev -> enforceCompatibility(mode, prev, request.getSchemaJson()));

        EventSchemaVersion version = EventSchemaVersion.builder()
                .eventTypeId(eventTypeId)
                .version(nextVersion)
                .schemaJson(request.getSchemaJson())
                .fingerprint(fp)
                .status(SchemaStatus.DRAFT)
                .compatibilityMode(mode)
                .description(request.getDescription())
                .createdBy(userId)
                .build();

        version = versionRepository.saveAndFlush(version);
        log.info("Created schema version {} for event type '{}'", nextVersion, eventType.getName());

        // Compute diff with previous version
        if (nextVersion > 1) {
            computeAndSaveDiff(eventTypeId, nextVersion - 1, version);
        }

        meterRegistry.counter("schema_versions_created_total",
                "event_type", eventType.getName()).increment();

        return mapVersionResponse(version);
    }

    public List<EventSchemaVersionResponse> listSchemaVersions(UUID eventTypeId) {
        EventTypeCatalog eventType = catalogRepository.findById(eventTypeId)
                .orElseThrow(() -> new NotFoundException("Event type not found"));
        validateProjectOwnership(eventType.getProjectId());

        return versionRepository.findByEventTypeIdOrderByVersionDesc(eventTypeId).stream()
                .map(this::mapVersionResponse)
                .collect(Collectors.toList());
    }

    public EventSchemaVersionResponse getSchemaVersion(UUID versionId) {
        EventSchemaVersion version = versionRepository.findById(versionId)
                .orElseThrow(() -> new NotFoundException("Schema version not found"));
        EventTypeCatalog eventType = catalogRepository.findById(version.getEventTypeId())
                .orElseThrow(() -> new NotFoundException("Event type not found"));
        validateProjectOwnership(eventType.getProjectId());
        return mapVersionResponse(version);
    }

    @Auditable(action = AuditAction.UPDATE, resourceType = "SchemaVersion")
    @Transactional
    public EventSchemaVersionResponse promoteSchema(UUID versionId) {
        EventSchemaVersion version = versionRepository.findById(versionId)
                .orElseThrow(() -> new NotFoundException("Schema version not found"));
        EventTypeCatalog eventType = catalogRepository.findById(version.getEventTypeId())
                .orElseThrow(() -> new NotFoundException("Event type not found"));
        validateProjectOwnership(eventType.getProjectId());

        if (version.getStatus() == SchemaStatus.ACTIVE) {
            return mapVersionResponse(version);
        }

        // Deprecate current active version
        versionRepository.findActiveByEventTypeId(version.getEventTypeId())
                .ifPresent(active -> {
                    active.setStatus(SchemaStatus.DEPRECATED);
                    versionRepository.save(active);
                    log.info("Deprecated schema version {} for event type '{}'", active.getVersion(), eventType.getName());
                });

        version.setStatus(SchemaStatus.ACTIVE);
        version = versionRepository.saveAndFlush(version);
        log.info("Promoted schema version {} to ACTIVE for event type '{}'", version.getVersion(), eventType.getName());

        return mapVersionResponse(version);
    }

    @Auditable(action = AuditAction.UPDATE, resourceType = "SchemaVersion")
    @Transactional
    public EventSchemaVersionResponse deprecateSchema(UUID versionId) {
        EventSchemaVersion version = versionRepository.findById(versionId)
                .orElseThrow(() -> new NotFoundException("Schema version not found"));
        EventTypeCatalog eventType = catalogRepository.findById(version.getEventTypeId())
                .orElseThrow(() -> new NotFoundException("Event type not found"));
        validateProjectOwnership(eventType.getProjectId());

        version.setStatus(SchemaStatus.DEPRECATED);
        version = versionRepository.saveAndFlush(version);
        return mapVersionResponse(version);
    }

    // ── Schema Changes ──

    @Transactional(readOnly = true)
    public List<SchemaChangeResponse> listSchemaChanges(UUID eventTypeId) {
        EventTypeCatalog eventType = catalogRepository.findById(eventTypeId)
                .orElseThrow(() -> new NotFoundException("Event type not found"));
        validateProjectOwnership(eventType.getProjectId());

        return changeRepository.findByEventTypeIdOrderByCreatedAtDesc(eventTypeId).stream()
                .map(this::mapChangeResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<SchemaChangeResponse> listProjectSchemaChanges(UUID projectId) {
        validateProjectOwnership(projectId);
        return changeRepository.findByProjectIdWithDetails(projectId).stream()
                .map(this::mapChangeResponse)
                .collect(Collectors.toList());
    }

    // ── Private helpers ──

    /**
     * Refuses a version that breaks the promise its mode makes about the one before it.
     *
     * <p>The registry stored a compatibility mode from the day it shipped, returned it on every
     * response, and never once read it: a project could declare FULL and then post a schema that
     * deleted every required property, and the only trace was a WARN line saying the change was
     * breaking — after the version had been written. The check is the same diff the schema-changes
     * board is built from, judged by {@link CompatibilityMode#violations}.
     *
     * <p>Nothing declares a mode by accident. It defaults to NONE, auto-discovered schemas get
     * NONE, and NONE checks nothing — so this can only refuse a project that asked to be refused.
     */
    private void enforceCompatibility(CompatibilityMode mode, EventSchemaVersion previous, String schemaJson) {
        if (mode == CompatibilityMode.NONE) {
            return;
        }

        List<String> violations;
        try {
            violations = mode.violations(JsonSchemaUtils.diff(previous.getSchemaJson(), schemaJson));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Could not compare this schema with version "
                    + previous.getVersion() + ": " + e.getMessage());
        }
        if (violations.isEmpty()) {
            return;
        }

        meterRegistry.counter("schema_compatibility_rejected_total", "mode", mode.name()).increment();
        throw new IllegalArgumentException(
                "Schema is not " + mode + "-compatible with version " + previous.getVersion()
                        + ": " + String.join("; ", violations));
    }

    private void computeAndSaveDiff(UUID eventTypeId, int previousVersion, EventSchemaVersion newVersion) {
        try {
            Optional<EventSchemaVersion> prevOpt = versionRepository.findByEventTypeIdAndVersion(eventTypeId, previousVersion);
            if (prevOpt.isEmpty()) return;

            EventSchemaVersion prev = prevOpt.get();
            JsonSchemaUtils.SchemaDiff schemaDiff = JsonSchemaUtils.diff(prev.getSchemaJson(), newVersion.getSchemaJson());

            SchemaChange change = SchemaChange.builder()
                    .eventTypeId(eventTypeId)
                    .fromVersionId(prev.getId())
                    .toVersionId(newVersion.getId())
                    .changeSummary(JsonSchemaUtils.diffToJson(schemaDiff))
                    .breaking(schemaDiff.breaking())
                    .build();
            changeRepository.save(change);

            if (schemaDiff.breaking()) {
                log.warn("Breaking schema change detected for event type ID {} (v{} → v{})",
                        eventTypeId, previousVersion, newVersion.getVersion());
            }
        } catch (Exception e) {
            log.warn("Failed to compute schema diff: {}", e.getMessage());
        }
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

    private EventTypeCatalogResponse mapCatalogResponse(EventTypeCatalog entity) {
        int latestVersion = versionRepository.findMaxVersionByEventTypeId(entity.getId());
        Optional<EventSchemaVersion> active = versionRepository.findActiveByEventTypeId(entity.getId());

        boolean hasBreaking = changeRepository.findByEventTypeIdOrderByCreatedAtDesc(entity.getId())
                .stream().anyMatch(c -> Boolean.TRUE.equals(c.getBreaking()));

        return EventTypeCatalogResponse.builder()
                .id(entity.getId())
                .projectId(entity.getProjectId())
                .name(entity.getName())
                .description(entity.getDescription())
                .latestVersion(latestVersion > 0 ? latestVersion : null)
                .activeVersionStatus(active.map(v -> v.getStatus().name()).orElse(null))
                .hasBreakingChanges(hasBreaking)
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    private EventSchemaVersionResponse mapVersionResponse(EventSchemaVersion entity) {
        return EventSchemaVersionResponse.builder()
                .id(entity.getId())
                .eventTypeId(entity.getEventTypeId())
                .version(entity.getVersion())
                .schemaJson(entity.getSchemaJson())
                .fingerprint(entity.getFingerprint())
                .status(entity.getStatus().name())
                .compatibilityMode(entity.getCompatibilityMode())
                .description(entity.getDescription())
                .createdBy(entity.getCreatedBy())
                .createdAt(entity.getCreatedAt())
                .build();
    }

    private SchemaChangeResponse mapChangeResponse(SchemaChange entity) {
        return SchemaChangeResponse.builder()
                .id(entity.getId())
                .eventTypeId(entity.getEventTypeId())
                .eventTypeName(entity.getEventType() != null ? entity.getEventType().getName() : null)
                .fromVersionId(entity.getFromVersionId())
                .fromVersion(entity.getFromVersion() != null ? entity.getFromVersion().getVersion() : null)
                .toVersionId(entity.getToVersionId())
                .toVersion(entity.getToVersion() != null ? entity.getToVersion().getVersion() : null)
                .changeSummary(entity.getChangeSummary())
                .breaking(entity.getBreaking())
                .createdAt(entity.getCreatedAt())
                .build();
    }
}

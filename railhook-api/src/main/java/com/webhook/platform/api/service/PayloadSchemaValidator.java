package com.webhook.platform.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.domain.entity.EventSchemaVersion;
import com.webhook.platform.api.domain.entity.EventTypeCatalog;
import com.webhook.platform.api.domain.enums.CompatibilityMode;
import com.webhook.platform.api.domain.enums.SchemaStatus;
import com.webhook.platform.api.domain.repository.EventSchemaVersionRepository;
import com.webhook.platform.api.domain.repository.EventTypeCatalogRepository;
import com.webhook.platform.common.util.JsonSchemaUtils;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The two things the ingest path asks of the schema registry. Separate from
 * {@link SchemaRegistryService}, which is the dashboard's CRUD over the same tables: this runs on
 * every event of a project with validation on.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PayloadSchemaValidator {

    private final EventTypeCatalogRepository catalogRepository;
    private final EventSchemaVersionRepository versionRepository;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    /**
     * Records an unseen event type and infers a DRAFT schema from the first payload it carries.
     * Never blocks ingestion: a project that has not declared its types still gets its events.
     */
    @Transactional
    public void autoDiscover(UUID projectId, String eventTypeName, String payloadJson) {
        try {
            EventTypeCatalog eventType = catalogRepository
                    .findByProjectIdAndName(projectId, eventTypeName)
                    .orElseGet(() -> catalogRepository.saveAndFlush(EventTypeCatalog.builder()
                            .projectId(projectId)
                            .name(eventTypeName)
                            .description("Auto-discovered from ingested event")
                            .build()));

            if (versionRepository.findMaxVersionByEventTypeId(eventType.getId()) != 0) {
                return;
            }

            String inferredSchema = objectMapper.writeValueAsString(JsonSchemaUtils.inferSchema(payloadJson));
            versionRepository.saveAndFlush(EventSchemaVersion.builder()
                    .eventTypeId(eventType.getId())
                    .version(1)
                    .schemaJson(inferredSchema)
                    .fingerprint(JsonSchemaUtils.fingerprint(inferredSchema))
                    .status(SchemaStatus.DRAFT)
                    .compatibilityMode(CompatibilityMode.NONE)
                    .description("Auto-inferred from first event payload")
                    .build());

            log.info("Auto-discovered event type '{}' with inferred DRAFT schema", eventTypeName);
            meterRegistry.counter("schema_auto_discovered_total", "event_type", eventTypeName).increment();
        } catch (Exception e) {
            log.warn("Schema auto-discovery failed for event type '{}': {}", eventTypeName, e.getMessage());
        }
    }

    /** Empty when the type is unknown or has no active schema: there is nothing to validate against. */
    public List<String> validate(UUID projectId, String eventTypeName, String payloadJson) {
        Optional<EventSchemaVersion> activeSchema = catalogRepository
                .findByProjectIdAndName(projectId, eventTypeName)
                .flatMap(eventType -> versionRepository.findActiveByEventTypeId(eventType.getId()));
        if (activeSchema.isEmpty()) {
            return List.of();
        }

        List<String> errors = JsonSchemaUtils.validate(payloadJson, activeSchema.get().getSchemaJson());
        if (!errors.isEmpty()) {
            meterRegistry.counter("schema_validation_failures_total", "event_type", eventTypeName).increment();
        }
        return errors;
    }
}

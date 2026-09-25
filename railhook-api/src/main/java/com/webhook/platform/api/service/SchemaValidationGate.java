package com.webhook.platform.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.domain.entity.Project;
import com.webhook.platform.api.domain.enums.SchemaValidationPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/** WARN returns the errors on the ingest response: the sender is the one who can fix the payload. */
@Component
@RequiredArgsConstructor
@Slf4j
public class SchemaValidationGate {

    private final PayloadSchemaValidator payloadSchemaValidator;
    private final ObjectMapper objectMapper;

    public List<String> check(Project project, UUID projectId, String eventType, Object payload) {
        if (project == null || !Boolean.TRUE.equals(project.getSchemaValidationEnabled())) {
            return List.of();
        }

        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to serialize event payload", e);
        }

        payloadSchemaValidator.autoDiscover(projectId, eventType, payloadJson);

        List<String> errors = payloadSchemaValidator.validate(projectId, eventType, payloadJson);
        if (errors.isEmpty()) {
            return List.of();
        }
        log.warn("Schema validation failed for event type '{}': {}", eventType, errors);
        if (project.getSchemaValidationPolicy() == SchemaValidationPolicy.BLOCK) {
            throw new IllegalArgumentException("Schema validation failed: " + String.join("; ", errors));
        }
        return errors;
    }
}

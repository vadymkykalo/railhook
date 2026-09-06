package com.webhook.platform.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.domain.entity.Project;
import com.webhook.platform.api.domain.enums.SchemaValidationPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Checks an event's payload against the schema registered for its type, before the event is
 * stored. A project with validation off is let through untouched.
 *
 * <p>The policy decides what a failure costs: BLOCK refuses the event; WARN accepts it and hands
 * the errors back to the caller, who gets them on the ingest response.
 *
 * <p>That last part is the point of the return value. This used to be {@code void}, and WARN meant
 * one {@code log.warn} on the server — its javadoc claimed the errors were "recorded" and nothing
 * was. Which left WARN the strictly worse of the two policies to choose: a person who wanted to
 * find out their payloads had drifted from their schema, without breaking their own producers to
 * find out, learned nothing they could act on unless they had server logs open. The party who can
 * fix the payload is the one sending it, so that is who the errors go to.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SchemaValidationGate {

    private final PayloadSchemaValidator payloadSchemaValidator;
    private final ObjectMapper objectMapper;

    /**
     * @return the validation errors this event was let through with, empty when there are none or
     *         when the project has validation off. BLOCK throws instead of returning.
     */
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

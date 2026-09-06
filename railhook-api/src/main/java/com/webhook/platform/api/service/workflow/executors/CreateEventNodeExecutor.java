package com.webhook.platform.api.service.workflow.executors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.webhook.platform.api.dto.EventIngestRequest;
import com.webhook.platform.api.dto.EventIngestResponse;
import com.webhook.platform.api.service.EventIngestService;
import com.webhook.platform.api.service.workflow.NodeExecutor;
import com.webhook.platform.api.service.workflow.StepResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Create Event node — emits a new event into the platform pipeline.
 * The event goes through the full flow: EventIngestService → subscriptions → deliveries → outbox → Kafka → worker.
 * This allows workflows to trigger the entire outgoing webhook pipeline.
 *
 * Config:
 *   - projectId (required): UUID of the project to emit the event into
 *   - eventType (required): the event type string (e.g. "order.completed")
 *   - payloadTemplate (optional): if set, used as event data; otherwise forwards workflow input
 */
@Component
@Slf4j
public class CreateEventNodeExecutor implements NodeExecutor {

    private final EventIngestService eventIngestService;
    private final ObjectMapper objectMapper;

    public CreateEventNodeExecutor(@Lazy EventIngestService eventIngestService, ObjectMapper objectMapper) {
        this.eventIngestService = eventIngestService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getType() {
        return "createEvent";
    }

    @Override
    public StepResult execute(JsonNode nodeConfig, JsonNode input) {
        try {
            // Project ID
            String projectIdStr = nodeConfig.has("projectId") ? nodeConfig.get("projectId").asText() : null;
            if (projectIdStr == null || projectIdStr.isBlank()) {
                return StepResult.failed("Create Event node: projectId is required");
            }
            UUID projectId;
            try {
                projectId = UUID.fromString(projectIdStr);
            } catch (IllegalArgumentException e) {
                return StepResult.failed("Create Event node: invalid projectId format");
            }

            // Event type
            String eventType = nodeConfig.has("eventType") ? nodeConfig.get("eventType").asText() : null;
            if (eventType == null || eventType.isBlank()) {
                return StepResult.failed("Create Event node: eventType is required");
            }

            // Event data: use payloadTemplate if configured, otherwise forward input
            JsonNode eventData;
            if (nodeConfig.has("payloadTemplate") && !nodeConfig.get("payloadTemplate").isNull()) {
                JsonNode tmpl = nodeConfig.get("payloadTemplate");
                if (tmpl.isTextual()) {
                    try {
                        eventData = objectMapper.readTree(tmpl.asText());
                    } catch (Exception e) {
                        eventData = input != null ? input : objectMapper.createObjectNode();
                    }
                } else {
                    eventData = tmpl;
                }
            } else {
                eventData = input != null ? input : objectMapper.createObjectNode();
            }

            // Create the event through full pipeline
            EventIngestRequest request = EventIngestRequest.builder()
                    .type(eventType)
                    .data(eventData)
                    .build();

            EventIngestResponse response = eventIngestService.ingestEvent(projectId, request, null);

            log.info("Workflow created event: {} (type={}) with {} deliveries in project {}",
                    response.getEventId(), eventType, response.getDeliveriesCreated(), projectId);

            ObjectNode output = objectMapper.createObjectNode();
            output.put("eventId", response.getEventId().toString());
            output.put("eventType", eventType);
            output.put("deliveriesCreated", response.getDeliveriesCreated());
            output.put("projectId", projectId.toString());
            return StepResult.success(output);
        } catch (Exception e) {
            log.error("Create Event node execution failed: {}", e.getMessage(), e);
            return StepResult.failed("Create Event error: " + e.getMessage());
        }
    }
}

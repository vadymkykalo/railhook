package com.webhook.platform.api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.domain.entity.Event;
import com.webhook.platform.api.domain.enums.DiffType;
import com.webhook.platform.api.domain.repository.EventRepository;
import com.webhook.platform.api.domain.repository.ProjectRepository;
import com.webhook.platform.api.dto.EventDiffResponse;
import com.webhook.platform.api.exception.NotFoundException;
import com.webhook.platform.api.tenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class EventDiffService {

    private final EventRepository eventRepository;
    private final ProjectRepository projectRepository;
    private final PiiMaskingService piiMaskingService;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public EventDiffResponse diff(UUID projectId, UUID leftEventId, UUID rightEventId,
                                   boolean sanitize) {
        UUID organizationId = TenantContext.require();
        projectRepository.findById(projectId)
                .filter(p -> p.getOrganizationId().equals(organizationId))
                .orElseThrow(() -> new NotFoundException("Project not found"));

        Event leftEvent = eventRepository.findById(leftEventId)
                .filter(e -> e.getProjectId().equals(projectId))
                .orElseThrow(() -> new NotFoundException("Left event not found"));

        Event rightEvent = eventRepository.findById(rightEventId)
                .filter(e -> e.getProjectId().equals(projectId))
                .orElseThrow(() -> new NotFoundException("Right event not found"));

        String leftPayload = leftEvent.getDecompressedPayload();
        String rightPayload = rightEvent.getDecompressedPayload();

        if (sanitize) {
            leftPayload = piiMaskingService.sanitizePayload(projectId, leftPayload);
            rightPayload = piiMaskingService.sanitizePayload(projectId, rightPayload);
        }

        List<EventDiffResponse.DiffEntry> diffs = computeDiffs(leftPayload, rightPayload);

        return EventDiffResponse.builder()
                .leftEventId(leftEventId)
                .rightEventId(rightEventId)
                .eventType(leftEvent.getEventType())
                .leftCreatedAt(leftEvent.getCreatedAt())
                .rightCreatedAt(rightEvent.getCreatedAt())
                .leftPayload(leftPayload)
                .rightPayload(rightPayload)
                .diffs(diffs)
                .build();
    }

    private List<EventDiffResponse.DiffEntry> computeDiffs(String leftJson, String rightJson) {
        List<EventDiffResponse.DiffEntry> diffs = new ArrayList<>();
        try {
            JsonNode leftNode = objectMapper.readTree(leftJson);
            JsonNode rightNode = objectMapper.readTree(rightJson);
            compareNodes(leftNode, rightNode, "$", diffs);
        } catch (Exception e) {
            log.warn("Failed to compute JSON diff: {}", e.getMessage());
            if (!leftJson.equals(rightJson)) {
                diffs.add(EventDiffResponse.DiffEntry.builder()
                        .path("$")
                        .type(DiffType.CHANGED)
                        .leftValue(leftJson)
                        .rightValue(rightJson)
                        .build());
            }
        }
        return diffs;
    }

    private void compareNodes(JsonNode left, JsonNode right, String path,
                               List<EventDiffResponse.DiffEntry> diffs) {
        if (left == null && right == null) {
            return;
        }

        if (left == null) {
            diffs.add(EventDiffResponse.DiffEntry.builder()
                    .path(path).type(DiffType.ADDED).rightValue(nodeToValue(right)).build());
            return;
        }

        if (right == null) {
            diffs.add(EventDiffResponse.DiffEntry.builder()
                    .path(path).type(DiffType.REMOVED).leftValue(nodeToValue(left)).build());
            return;
        }

        if (left.isObject() && right.isObject()) {
            Iterator<String> fieldNames = left.fieldNames();
            while (fieldNames.hasNext()) {
                String field = fieldNames.next();
                String childPath = path + "." + field;
                if (right.has(field)) {
                    compareNodes(left.get(field), right.get(field), childPath, diffs);
                } else {
                    diffs.add(EventDiffResponse.DiffEntry.builder()
                            .path(childPath).type(DiffType.REMOVED).leftValue(nodeToValue(left.get(field))).build());
                }
            }
            Iterator<String> rightFields = right.fieldNames();
            while (rightFields.hasNext()) {
                String field = rightFields.next();
                if (!left.has(field)) {
                    String childPath = path + "." + field;
                    diffs.add(EventDiffResponse.DiffEntry.builder()
                            .path(childPath).type(DiffType.ADDED).rightValue(nodeToValue(right.get(field))).build());
                }
            }
        } else if (left.isArray() && right.isArray()) {
            int maxSize = Math.max(left.size(), right.size());
            for (int i = 0; i < maxSize; i++) {
                String childPath = path + "[" + i + "]";
                JsonNode leftElem = i < left.size() ? left.get(i) : null;
                JsonNode rightElem = i < right.size() ? right.get(i) : null;
                compareNodes(leftElem, rightElem, childPath, diffs);
            }
        } else if (!left.equals(right)) {
            diffs.add(EventDiffResponse.DiffEntry.builder()
                    .path(path).type(DiffType.CHANGED)
                    .leftValue(nodeToValue(left)).rightValue(nodeToValue(right)).build());
        }
    }

    private Object nodeToValue(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isTextual()) return node.asText();
        if (node.isNumber()) return node.numberValue();
        if (node.isBoolean()) return node.asBoolean();
        return node.toString();
    }
}

package com.webhook.platform.api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.domain.enums.DiffType;
import com.webhook.platform.api.dto.JsonDiffEntry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Unparseable input falls back to a single whole-document entry rather than an error. */
@Slf4j
@Component
@RequiredArgsConstructor
public class JsonDiffCalculator {

    private final ObjectMapper objectMapper;

    public List<JsonDiffEntry> diff(String leftJson, String rightJson) {
        List<JsonDiffEntry> diffs = new ArrayList<>();
        try {
            JsonNode leftNode = objectMapper.readTree(leftJson);
            JsonNode rightNode = objectMapper.readTree(rightJson);
            compareNodes(leftNode, rightNode, "$", diffs);
        } catch (Exception e) {
            log.warn("Failed to compute JSON diff: {}", e.getMessage());
            if (leftJson != null && !leftJson.equals(rightJson)) {
                diffs.add(JsonDiffEntry.builder()
                        .path("$")
                        .type(DiffType.CHANGED)
                        .leftValue(leftJson)
                        .rightValue(rightJson)
                        .build());
            }
        }
        return diffs;
    }

    private void compareNodes(JsonNode left, JsonNode right, String path, List<JsonDiffEntry> diffs) {
        if (left == null && right == null) {
            return;
        }

        if (left == null) {
            diffs.add(JsonDiffEntry.builder()
                    .path(path).type(DiffType.ADDED).rightValue(nodeToValue(right)).build());
            return;
        }

        if (right == null) {
            diffs.add(JsonDiffEntry.builder()
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
                    diffs.add(JsonDiffEntry.builder()
                            .path(childPath).type(DiffType.REMOVED).leftValue(nodeToValue(left.get(field))).build());
                }
            }
            Iterator<String> rightFields = right.fieldNames();
            while (rightFields.hasNext()) {
                String field = rightFields.next();
                if (!left.has(field)) {
                    String childPath = path + "." + field;
                    diffs.add(JsonDiffEntry.builder()
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
            diffs.add(JsonDiffEntry.builder()
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

package com.webhook.platform.api.service.workflow.executors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.webhook.platform.api.domain.entity.Transformation;
import com.webhook.platform.api.domain.repository.TransformationRepository;
import com.webhook.platform.api.service.transform.TemplateTransformer;
import com.webhook.platform.api.service.transform.TransformationRunner;
import com.webhook.platform.common.transform.TransformRequest;
import com.webhook.platform.common.transform.TransformationKind;
import com.webhook.platform.api.service.workflow.NodeExecutor;
import com.webhook.platform.api.service.workflow.StepResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** An unresolvable transformation fails the step rather than passing the raw payload through. */
@Component
@Slf4j
@RequiredArgsConstructor
public class TransformNodeExecutor implements NodeExecutor {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([^}]+)}}");

    private final ObjectMapper objectMapper;
    private final TransformationRepository transformationRepository;
    private final TemplateTransformer templateTransformer;
    private final TransformationRunner runner;

    @Override
    public String getType() {
        return "transform";
    }

    @Override
    public StepResult execute(JsonNode nodeConfig, JsonNode input) {
        JsonNode reference = nodeConfig.get("transformationId");
        if (reference != null && !reference.isNull() && !reference.asText().isBlank()) {
            return applySaved(reference.asText().trim(), input);
        }
        return applyInline(nodeConfig, input);
    }

    private StepResult applySaved(String rawId, JsonNode input) {
        UUID transformationId;
        try {
            transformationId = UUID.fromString(rawId);
        } catch (IllegalArgumentException e) {
            return StepResult.failed("Not a transformation id: " + rawId);
        }

        Optional<Transformation> found = transformationRepository.findById(transformationId);
        if (found.isEmpty()) {
            return StepResult.failed("Transformation not found: " + transformationId);
        }
        Transformation transformation = found.get();
        if (Boolean.FALSE.equals(transformation.getEnabled())) {
            return StepResult.failed("Transformation is disabled: " + transformation.getName());
        }

        try {
            // No Delivery behind a workflow node, so a cancel stops the step instead.
            TransformationRunner.Result result = runner.run(
                    transformation.getKind(), transformation.getTemplate(),
                    TransformRequest.builder()
                            .payload(input == null ? "null" : input.toString())
                            .eventType("workflow.step")
                            .eventId(transformationId.toString())
                            .timestamp(java.time.Instant.now())
                            .direction("OUTGOING")
                            .build());
            if (result.cancelled()) {
                return StepResult.failed("Transformation '" + transformation.getName()
                        + "' cancelled the step: "
                        + (result.cancelReason() == null ? "no reason given" : result.cancelReason()));
            }
            return StepResult.success(result.payload());
        } catch (Exception e) {
            log.error("Saved transformation {} failed: {}", transformationId, e.getMessage(), e);
            return StepResult.failed("Transformation '" + transformation.getName() + "' failed: " + e.getMessage());
        }
    }

    private StepResult applyInline(JsonNode nodeConfig, JsonNode input) {
        try {
            JsonNode templateNode = nodeConfig.get("template");
            if (templateNode == null || templateNode.isNull()) {
                return StepResult.success(input);
            }

            String template = templateNode.isTextual() ? templateNode.textValue() : templateNode.toString();

            if (template.trim().startsWith("{")) {
                JsonNode templateJson = objectMapper.readTree(template);
                JsonNode resolved = resolvePlaceholders(templateJson, input);
                return StepResult.success(resolved);
            }

            String resolved = resolvePlaceholdersInString(template, input);
            return StepResult.success(objectMapper.createObjectNode().put("result", resolved));
        } catch (Exception e) {
            log.error("Transform node execution failed: {}", e.getMessage(), e);
            return StepResult.failed("Transform error: " + e.getMessage());
        }
    }

    private JsonNode resolvePlaceholders(JsonNode template, JsonNode input) {
        if (template.isObject()) {
            ObjectNode result = objectMapper.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = template.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                result.set(field.getKey(), resolvePlaceholders(field.getValue(), input));
            }
            return result;
        }
        if (template.isTextual()) {
            String text = template.textValue();
            String resolved = resolvePlaceholdersInString(text, input);
            return objectMapper.getNodeFactory().textNode(resolved);
        }
        return template;
    }

    private String resolvePlaceholdersInString(String template, JsonNode input) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String path = matcher.group(1).trim();
            JsonNode value = resolvePath(path, input);
            String replacement = (value != null && !value.isMissingNode() && !value.isNull())
                    ? (value.isTextual() ? value.textValue() : value.toString())
                    : "";
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private JsonNode resolvePath(String path, JsonNode root) {
        String[] segments = path.split("\\.");
        JsonNode current = root;
        for (String segment : segments) {
            if (current == null || current.isMissingNode() || current.isNull()) return null;
            current = current.path(segment);
        }
        return current;
    }
}

package com.webhook.platform.api.service.workflow.executors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.webhook.platform.api.domain.entity.Transformation;
import com.webhook.platform.api.domain.repository.TransformationRepository;
import com.webhook.platform.api.service.transform.TemplateTransformer;
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

/**
 * Reshapes the data passing through a workflow, one of two ways.
 *
 * <p><strong>A saved transformation</strong> — {@code transformationId} names one from the
 * project's Transformations page, the same objects a rule action points at, written in
 * {@code ${$.json.path}} and run by {@link TemplateTransformer}. This is what a project with a
 * library of them wants, and until it existed the library was invisible from the canvas: the
 * only way to reuse a transformation in a workflow was to retype it, in a different syntax.
 *
 * <p><strong>An inline template</strong> — {@code template}, in this node's own
 * {@code {{field.path}}} placeholders, for a one-off reshape that does not deserve a name.
 *
 * <p>The two syntaxes are not interchangeable and this node does not try to guess which it was
 * handed: the reference decides. A node carrying both — which is what switching a node from
 * inline to saved leaves behind — runs the saved one.
 *
 * <p>A reference that cannot be resolved fails the step. Passing the payload through instead
 * would send a raw event to a destination promised a reshaped one and report it as a success,
 * which is the worst of the available outcomes and the hardest to notice.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class TransformNodeExecutor implements NodeExecutor {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([^}]+)}}");

    private final ObjectMapper objectMapper;
    private final TransformationRepository transformationRepository;
    private final TemplateTransformer templateTransformer;

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

        // @TenantId scopes this to the caller's organization, and the engine's pool carries the
        // tenant across (TenantPropagatingTaskDecorator), so there is no id to check by hand.
        Optional<Transformation> found = transformationRepository.findById(transformationId);
        if (found.isEmpty()) {
            return StepResult.failed("Transformation not found: " + transformationId);
        }
        Transformation transformation = found.get();
        if (Boolean.FALSE.equals(transformation.getEnabled())) {
            return StepResult.failed("Transformation is disabled: " + transformation.getName());
        }

        try {
            return StepResult.success(templateTransformer.apply(transformation.getTemplate(), input));
        } catch (Exception e) {
            log.error("Saved transformation {} failed: {}", transformationId, e.getMessage(), e);
            return StepResult.failed("Transformation '" + transformation.getName() + "' failed: " + e.getMessage());
        }
    }

    private StepResult applyInline(JsonNode nodeConfig, JsonNode input) {
        try {
            JsonNode templateNode = nodeConfig.get("template");
            if (templateNode == null || templateNode.isNull()) {
                return StepResult.success(input); // nothing configured yet = pass through
            }

            String template = templateNode.isTextual() ? templateNode.textValue() : templateNode.toString();

            // If template is a JSON string, parse and resolve placeholders in values
            if (template.trim().startsWith("{")) {
                JsonNode templateJson = objectMapper.readTree(template);
                JsonNode resolved = resolvePlaceholders(templateJson, input);
                return StepResult.success(resolved);
            }

            // Plain string template — resolve and wrap
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
        return template; // numbers, booleans, arrays — pass through
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

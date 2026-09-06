package com.webhook.platform.api.service.transform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;
import com.jayway.jsonpath.spi.json.JacksonJsonNodeJsonProvider;
import com.jayway.jsonpath.spi.mapper.JacksonMappingProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The language a saved Transformation is written in: a JSON document whose string values may be
 * {@code ${$.json.path}} expressions against the incoming payload.
 *
 * <p>A whole string that is one expression takes the referenced value with its type — a number
 * stays a number, an object stays an object. An expression embedded in surrounding text is
 * substituted into it and the result is a string. A path that matches nothing yields null rather
 * than an error, because a template is usually written against one example payload and the
 * fields it did not mention are not a failure.
 *
 * <p>Pulled out of {@code TransformPreviewService} so that the preview a person reads before
 * saving and the workflow node that runs the saved thing are the same code. They were not, and
 * the third copy — {@code PayloadTransformService} in the worker, which is what actually
 * transforms a delivery — is still its own: the modules do not share a jar, and reconciling
 * them is a change to delivery behaviour rather than to this.
 */
@Slf4j
@Component
public class TemplateTransformer {

    private static final Pattern JSONPATH_PATTERN = Pattern.compile("\\$\\{([^}]+)}");

    private final ObjectMapper objectMapper;
    private final Configuration jsonPathConfig;

    public TemplateTransformer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.jsonPathConfig = Configuration.builder()
                .jsonProvider(new JacksonJsonNodeJsonProvider())
                .mappingProvider(new JacksonMappingProvider())
                .options(Option.SUPPRESS_EXCEPTIONS)
                .build();
    }

    /** Applies a parsed template document to a source payload. */
    public JsonNode apply(JsonNode template, JsonNode source) {
        if (template.isObject()) {
            return applyToObject((ObjectNode) template, source);
        }
        if (template.isArray()) {
            return applyToArray((ArrayNode) template, source);
        }
        if (template.isTextual()) {
            return applyToText(template.asText(), source);
        }
        return template.deepCopy();
    }

    /** The same, for a template that is still text. Throws if it is not JSON. */
    public JsonNode apply(String template, JsonNode source) throws com.fasterxml.jackson.core.JsonProcessingException {
        return apply(objectMapper.readTree(template), source);
    }

    private ObjectNode applyToObject(ObjectNode template, JsonNode source) {
        ObjectNode result = objectMapper.createObjectNode();
        for (Map.Entry<String, JsonNode> field : template.properties()) {
            result.set(field.getKey(), apply(field.getValue(), source));
        }
        return result;
    }

    private ArrayNode applyToArray(ArrayNode template, JsonNode source) {
        ArrayNode result = objectMapper.createArrayNode();
        for (JsonNode element : template) {
            result.add(apply(element, source));
        }
        return result;
    }

    private JsonNode applyToText(String text, JsonNode source) {
        Matcher matcher = JSONPATH_PATTERN.matcher(text);
        // The whole value is one expression: keep whatever type it resolves to.
        if (matcher.matches()) {
            return evaluate(matcher.group(1), source);
        }
        if (matcher.find()) {
            matcher.reset();
            StringBuilder sb = new StringBuilder();
            while (matcher.find()) {
                JsonNode value = evaluate(matcher.group(1), source);
                String replacement = value != null
                        ? (value.isTextual() ? value.asText() : value.toString()) : "";
                matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
            }
            matcher.appendTail(sb);
            return objectMapper.getNodeFactory().textNode(sb.toString());
        }
        return objectMapper.getNodeFactory().textNode(text);
    }

    private JsonNode evaluate(String jsonPath, JsonNode source) {
        try {
            Object result = JsonPath.using(jsonPathConfig).parse(source).read(jsonPath);
            if (result == null) {
                return objectMapper.getNodeFactory().nullNode();
            }
            return result instanceof JsonNode node ? node : objectMapper.valueToTree(result);
        } catch (Exception e) {
            log.debug("JSONPath evaluation failed for '{}': {}", jsonPath, e.getMessage());
            return objectMapper.getNodeFactory().nullNode();
        }
    }
}

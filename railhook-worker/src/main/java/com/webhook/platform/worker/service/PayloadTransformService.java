package com.webhook.platform.worker.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;
import com.jayway.jsonpath.spi.json.JacksonJsonNodeJsonProvider;
import com.jayway.jsonpath.spi.mapper.JacksonMappingProvider;
import com.webhook.platform.common.transform.JavaScriptTransformEngine;
import com.webhook.platform.common.transform.ScriptTransformException;
import com.webhook.platform.common.transform.TransformOutcome;
import com.webhook.platform.common.transform.TransformRequest;
import com.webhook.platform.common.transform.TransformationKind;
import com.webhook.platform.worker.attempt.TransformedBody;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Iterator;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
public class PayloadTransformService {

    private final ObjectMapper objectMapper;
    private final JavaScriptTransformEngine scriptEngine;
    private final Counter transformFailedCounter;

    private static final Pattern JSONPATH_PATTERN = Pattern.compile("\\$\\{([^}]+)}");

    private final Configuration jsonPathConfig = Configuration.builder()
            .jsonProvider(new JacksonJsonNodeJsonProvider())
            .mappingProvider(new JacksonMappingProvider())
            .options(Option.SUPPRESS_EXCEPTIONS)
            .build();

    public PayloadTransformService(ObjectMapper objectMapper, MeterRegistry meterRegistry,
            JavaScriptTransformEngine scriptEngine) {
        this.objectMapper = objectMapper;
        this.scriptEngine = scriptEngine;
        this.transformFailedCounter = Counter.builder("transform_failed_total")
                .tag("component", "payload_transform_service")
                .register(meterRegistry);
    }

    // Scripts run on the engine the api previews with, so a preview cannot disagree with the
    // delivery. A failed script throws; a cancelled one is not a failure.
    public TransformedBody apply(TransformationCacheService.Resolved resolved, String body,
            TransformRequest context) {
        if (resolved == null || !resolved.isConfigured()) {
            return TransformedBody.of(body);
        }
        if (resolved.kind() != TransformationKind.JAVASCRIPT) {
            return TransformedBody.of(transform(body, resolved.source()));
        }

        try {
            TransformOutcome outcome = scriptEngine.run(resolved.source(), context);
            if (log.isDebugEnabled() && !outcome.console().isEmpty()) {
                outcome.console().forEach(line ->
                        log.debug("transformation console [{}] {}", line.level(), line.message()));
            }
            if (outcome.cancelled()) {
                return TransformedBody.cancelled(outcome.cancelReason());
            }
            return new TransformedBody(outcome.payload(), outcome.headers(), false, null);
        } catch (ScriptTransformException e) {
            transformFailedCounter.increment();
            String where = e.line() > 0 ? " at line " + e.line() : "";
            log.error("Configured script transformation failed ({}{}); refusing to fall back to "
                    + "the raw payload: {}", e.reason(), where, e.getMessage());
            throw new PayloadTransformException(
                    "Script transformation failed (" + e.reason() + where + "): " + e.getMessage(), e);
        }
    }

    /**
     * A blank template means no transformation. A configured template that cannot be applied
     * throws: callers must fail the attempt, never send the original payload, because the
     * template is often there to strip PII.
     */
    public String transform(String originalPayload, String template) {
        if (template == null || template.isBlank()) {
            return originalPayload;
        }

        try {
            JsonNode sourceNode = objectMapper.readTree(originalPayload);
            JsonNode templateNode = objectMapper.readTree(template);

            JsonNode resultNode = processNode(templateNode, sourceNode);
            return objectMapper.writeValueAsString(resultNode);
        } catch (Exception e) {
            transformFailedCounter.increment();
            log.error("Configured payload transformation failed; refusing to fall back to the " +
                    "raw payload: {}", e.getMessage());
            throw new PayloadTransformException("Payload transformation failed: " + e.getMessage(), e);
        }
    }

    private JsonNode processNode(JsonNode templateNode, JsonNode sourceNode) {
        if (templateNode.isObject()) {
            return processObject((ObjectNode) templateNode, sourceNode);
        } else if (templateNode.isArray()) {
            return processArray((ArrayNode) templateNode, sourceNode);
        } else if (templateNode.isTextual()) {
            return processTextValue(templateNode.asText(), sourceNode);
        } else {
            return templateNode.deepCopy();
        }
    }

    private ObjectNode processObject(ObjectNode templateObject, JsonNode sourceNode) {
        ObjectNode result = objectMapper.createObjectNode();
        
        Iterator<Map.Entry<String, JsonNode>> fields = templateObject.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            JsonNode processedValue = processNode(field.getValue(), sourceNode);
            result.set(field.getKey(), processedValue);
        }
        
        return result;
    }

    private ArrayNode processArray(ArrayNode templateArray, JsonNode sourceNode) {
        ArrayNode result = objectMapper.createArrayNode();
        
        for (JsonNode element : templateArray) {
            result.add(processNode(element, sourceNode));
        }
        
        return result;
    }

    private JsonNode processTextValue(String text, JsonNode sourceNode) {
        Matcher matcher = JSONPATH_PATTERN.matcher(text);
        
        if (matcher.matches()) {
            // A whole-value expression keeps the JSON type; an embedded one is interpolated as text.
            String jsonPath = matcher.group(1);
            return evaluateJsonPath(jsonPath, sourceNode);
        } else if (matcher.find()) {
            matcher.reset();
            StringBuffer sb = new StringBuffer();
            while (matcher.find()) {
                String jsonPath = matcher.group(1);
                JsonNode value = evaluateJsonPath(jsonPath, sourceNode);
                String replacement = value != null ? 
                        (value.isTextual() ? value.asText() : value.toString()) : "";
                matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
            }
            matcher.appendTail(sb);
            return objectMapper.getNodeFactory().textNode(sb.toString());
        } else {
            return objectMapper.getNodeFactory().textNode(text);
        }
    }

    /**
     * With SUPPRESS_EXCEPTIONS a well-formed path that matches nothing returns null, which is an
     * optional field. Anything that still throws is a malformed path and must propagate. This
     * once swallowed it, and receivers got signed bodies with silent nulls where a PII-stripping
     * template had misfired.
     */
    private JsonNode evaluateJsonPath(String jsonPath, JsonNode sourceNode) {
        Object result = JsonPath.using(jsonPathConfig).parse(sourceNode).read(jsonPath);
        if (result == null) {
            return objectMapper.getNodeFactory().nullNode();
        }
        if (result instanceof JsonNode) {
            return (JsonNode) result;
        }
        return objectMapper.valueToTree(result);
    }

    public boolean validateTemplate(String template) {
        if (template == null || template.isBlank()) {
            return true;
        }
        
        try {
            objectMapper.readTree(template);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}

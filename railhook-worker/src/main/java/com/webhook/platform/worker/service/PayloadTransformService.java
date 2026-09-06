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
    private final Counter transformFailedCounter;

    private static final Pattern JSONPATH_PATTERN = Pattern.compile("\\$\\{([^}]+)}");

    private final Configuration jsonPathConfig = Configuration.builder()
            .jsonProvider(new JacksonJsonNodeJsonProvider())
            .mappingProvider(new JacksonMappingProvider())
            .options(Option.SUPPRESS_EXCEPTIONS)
            .build();

    public PayloadTransformService(ObjectMapper objectMapper, MeterRegistry meterRegistry) {
        this.objectMapper = objectMapper;
        this.transformFailedCounter = Counter.builder("transform_failed_total")
                .tag("component", "payload_transform_service")
                .register(meterRegistry);
    }

    /**
     * Transforms the event payload using the provided template.
     * Template supports JSONPath expressions in ${...} syntax.
     * 
     * Example template:
     * {
     *   "event_id": "${$.id}",
     *   "customer": {
     *     "name": "${$.data.customer.name}",
     *     "email": "${$.data.customer.email}"
     *   },
     *   "timestamp": "${$.created_at}"
     * }
     * 
     * @param originalPayload The original event payload JSON
     * @param template The transformation template. {@code null}/blank means "no
     *                 transformation configured" — the original payload is fine to send.
     * @return Transformed payload JSON string
     * @throws PayloadTransformException if a template WAS configured (non-blank) but could
     *         not be applied — invalid source JSON, invalid template JSON, or any other
     *         processing failure. Callers must treat this as a failed delivery attempt
     *         (retryable), never fall back to {@code originalPayload}: the whole point of a
     *         configured transformation is often to strip PII before the payload leaves the
     *         platform.
     */
    public String transform(String originalPayload, String template) {
        if (template == null || template.isBlank()) {
            // No transformation configured — sending the payload as-is is correct, not a bug.
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
            // Entire value is a JSONPath expression - return the actual value
            String jsonPath = matcher.group(1);
            return evaluateJsonPath(jsonPath, sourceNode);
        } else if (matcher.find()) {
            // Text contains embedded JSONPath expressions - string interpolation
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
            // Plain text - return as-is
            return objectMapper.getNodeFactory().textNode(text);
        }
    }

    /**
     * Reads one {@code ${...}} expression, distinguishing "no such field" from "no such path".
     *
     * <p>The config carries {@code SUPPRESS_EXCEPTIONS}, so a well-formed path that matches
     * nothing already comes back as {@code null} without throwing — an ordinary optional field,
     * and it stays a JSON null. Anything that still throws is the path itself being malformed:
     * a typo the author made, which no amount of retrying fixes and which the caller must see.
     *
     * <p>This used to catch that and substitute a null too, at DEBUG. The receiver then got a
     * successfully delivered, successfully signed body with nulls where the data should have
     * been, and nothing anywhere said so — contradicting both {@code AttemptStore.buildBody}'s
     * "never return the untransformed payload" (a transformation is all-or-nothing) and
     * {@code AttemptRunner}'s invariant 4. The case it hurt most is the one
     * {@link #transform} names: a template whose job is to strip PII by whitelisting fields is
     * exactly the template whose silent misfire nobody notices.
     *
     * <p>Note the asymmetry this removes: {@code IncomingAttemptStore} evaluates its paths with
     * a raw {@code JsonPath.read} and has always thrown.
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

    /**
     * Validates a template by parsing it and checking for valid JSONPath expressions.
     * 
     * @param template The template to validate
     * @return true if valid, false otherwise
     */
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

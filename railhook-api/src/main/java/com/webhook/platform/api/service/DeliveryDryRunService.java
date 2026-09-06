package com.webhook.platform.api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;
import com.jayway.jsonpath.spi.json.JacksonJsonNodeJsonProvider;
import com.jayway.jsonpath.spi.mapper.JacksonMappingProvider;
import com.webhook.platform.api.domain.entity.Endpoint;
import com.webhook.platform.api.domain.entity.Transformation;
import com.webhook.platform.api.domain.repository.EndpointRepository;
import com.webhook.platform.api.domain.repository.TransformationRepository;
import com.webhook.platform.api.dto.DeliveryDryRunRequest;
import com.webhook.platform.api.dto.DeliveryDryRunResponse;
import com.webhook.platform.common.security.EncryptionKeyRegistry;
import com.webhook.platform.common.util.WebhookSignatureUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
public class DeliveryDryRunService {

    private final TransformationRepository transformationRepository;
    private final EndpointRepository endpointRepository;
    private final ObjectMapper objectMapper;
    private final EncryptionKeyRegistry encryptionKeyRegistry;

    private static final Pattern JSONPATH_PATTERN = Pattern.compile("\\$\\{([^}]+)}");

    private final Configuration jsonPathConfig = Configuration.builder()
            .jsonProvider(new JacksonJsonNodeJsonProvider())
            .mappingProvider(new JacksonMappingProvider())
            .options(Option.SUPPRESS_EXCEPTIONS)
            .build();

    public DeliveryDryRunService(
            TransformationRepository transformationRepository,
            EndpointRepository endpointRepository,
            ObjectMapper objectMapper,
            EncryptionKeyRegistry encryptionKeyRegistry) {
        this.transformationRepository = transformationRepository;
        this.endpointRepository = endpointRepository;
        this.objectMapper = objectMapper;
        this.encryptionKeyRegistry = encryptionKeyRegistry;
    }

    public DeliveryDryRunResponse dryRun(DeliveryDryRunRequest request) {
        List<String> errors = new ArrayList<>();
        String transformedPayload = null;
        String transformationName = null;
        Integer transformationVersion = null;
        String signature = null;
        String endpointUrl = null;
        Map<String, String> requestHeaders = new LinkedHashMap<>();

        // 1. Parse input payload
        JsonNode sourceNode;
        try {
            sourceNode = objectMapper.readTree(request.getPayload());
        } catch (Exception e) {
            errors.add("Invalid input JSON: " + e.getMessage());
            return DeliveryDryRunResponse.builder()
                    .success(false)
                    .errors(errors)
                    .build();
        }

        // 2. Resolve template: transformationId > payloadTemplate > passthrough
        String resolvedTemplate = null;

        if (request.getTransformationId() != null) {
            Optional<Transformation> transformOpt = transformationRepository.findById(request.getTransformationId());
            if (transformOpt.isEmpty()) {
                errors.add("Transformation not found: " + request.getTransformationId());
            } else {
                Transformation t = transformOpt.get();
                if (!t.getEnabled()) {
                    errors.add("Transformation is disabled: " + t.getName());
                } else {
                    resolvedTemplate = t.getTemplate();
                    transformationName = t.getName();
                    transformationVersion = t.getVersion();
                }
            }
        } else if (request.getPayloadTemplate() != null && !request.getPayloadTemplate().isBlank()) {
            resolvedTemplate = request.getPayloadTemplate();
        }

        // 3. Transform payload
        if (resolvedTemplate != null) {
            try {
                JsonNode templateNode = objectMapper.readTree(resolvedTemplate);
                JsonNode resultNode = processNode(templateNode, sourceNode);
                transformedPayload = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(resultNode);
            } catch (Exception e) {
                errors.add("Template transform error: " + e.getMessage());
                transformedPayload = request.getPayload();
            }
        } else {
            // Passthrough
            try {
                transformedPayload = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(sourceNode);
            } catch (Exception e) {
                transformedPayload = request.getPayload();
            }
        }

        // 4. Build headers
        long timestamp = System.currentTimeMillis();
        requestHeaders.put("Content-Type", "application/json");
        requestHeaders.put("User-Agent", "WebhookPlatform/1.0");
        requestHeaders.put("X-Timestamp", String.valueOf(timestamp));

        if (request.getEventType() != null) {
            requestHeaders.put("X-Event-Type", request.getEventType());
        }

        // 5. Compute HMAC signature if endpoint provided
        if (request.getEndpointId() != null) {
            Optional<Endpoint> endpointOpt = endpointRepository.findById(request.getEndpointId());
            if (endpointOpt.isEmpty()) {
                errors.add("Endpoint not found: " + request.getEndpointId());
            } else {
                Endpoint endpoint = endpointOpt.get();
                endpointUrl = endpoint.getUrl();
                try {
                    // Go through the registry, not CryptoUtils directly: the dry-run must
                    // resolve the endpoint's own key version and fall back across a rotation
                    // exactly as real delivery does. Reading a single raw key here made the
                    // dry-run report "Failed to compute signature" for any endpoint still
                    // encrypted under a previous version while delivery kept working.
                    String secret = encryptionKeyRegistry.decryptWithFallback(
                            endpoint.getSecretEncrypted(),
                            endpoint.getSecretIv(),
                            endpoint.getEncryptionKeyVersion());
                    String body = transformedPayload != null ? transformedPayload : request.getPayload();
                    signature = WebhookSignatureUtils.buildSignatureHeader(secret, timestamp, body);
                    requestHeaders.put("X-Signature", signature);
                } catch (Exception e) {
                    errors.add("Failed to compute signature: " + e.getMessage());
                }

                if (!endpoint.getEnabled()) {
                    errors.add("Warning: Endpoint is currently disabled");
                }
            }
        }

        // 6. Merge custom headers
        if (request.getCustomHeaders() != null && !request.getCustomHeaders().isBlank()) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, String> custom = objectMapper.readValue(request.getCustomHeaders(), Map.class);
                custom.forEach((key, value) -> {
                    if (key != null && value != null && !key.isBlank()) {
                        String keyLower = key.toLowerCase();
                        if (!keyLower.equals("host") && !keyLower.equals("content-length")
                                && !keyLower.equals("transfer-encoding")) {
                            requestHeaders.put(key, value);
                        }
                    }
                });
            } catch (Exception e) {
                errors.add("Invalid custom headers JSON: " + e.getMessage());
            }
        }

        return DeliveryDryRunResponse.builder()
                .transformedPayload(transformedPayload)
                .requestHeaders(requestHeaders)
                .signature(signature)
                .endpointUrl(endpointUrl)
                .success(errors.isEmpty())
                .errors(errors)
                .transformationName(transformationName)
                .transformationVersion(transformationVersion)
                .build();
    }

    // ── Template processing (mirrors PayloadTransformService in worker) ──

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
        var fields = templateObject.fields();
        while (fields.hasNext()) {
            var field = fields.next();
            result.set(field.getKey(), processNode(field.getValue(), sourceNode));
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
            String jsonPath = matcher.group(1);
            return evaluateJsonPath(jsonPath, sourceNode);
        } else if (matcher.find()) {
            matcher.reset();
            StringBuffer sb = new StringBuffer();
            while (matcher.find()) {
                String jsonPath = matcher.group(1);
                JsonNode value = evaluateJsonPath(jsonPath, sourceNode);
                String replacement = value != null
                        ? (value.isTextual() ? value.asText() : value.toString()) : "";
                matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
            }
            matcher.appendTail(sb);
            return objectMapper.getNodeFactory().textNode(sb.toString());
        } else {
            return objectMapper.getNodeFactory().textNode(text);
        }
    }

    private JsonNode evaluateJsonPath(String jsonPath, JsonNode sourceNode) {
        try {
            Object result = JsonPath.using(jsonPathConfig).parse(sourceNode).read(jsonPath);
            if (result == null) {
                return objectMapper.getNodeFactory().nullNode();
            }
            if (result instanceof JsonNode) {
                return (JsonNode) result;
            }
            return objectMapper.valueToTree(result);
        } catch (Exception e) {
            log.debug("JSONPath evaluation failed for '{}': {}", jsonPath, e.getMessage());
            return objectMapper.getNodeFactory().nullNode();
        }
    }
}

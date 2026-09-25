package com.webhook.platform.api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.domain.entity.Endpoint;
import com.webhook.platform.api.domain.entity.Transformation;
import com.webhook.platform.api.domain.repository.EndpointRepository;
import com.webhook.platform.api.domain.repository.TransformationRepository;
import com.webhook.platform.api.dto.DeliveryDryRunRequest;
import com.webhook.platform.api.dto.DeliveryDryRunResponse;
import com.webhook.platform.api.dto.TransformPreviewResponse;
import com.webhook.platform.api.service.transform.TransformationRunner;
import com.webhook.platform.common.security.EncryptionKeyRegistry;
import com.webhook.platform.common.transform.ScriptConsoleLine;
import com.webhook.platform.common.transform.ScriptTransformException;
import com.webhook.platform.common.transform.TransformRequest;
import com.webhook.platform.common.transform.TransformationKind;
import com.webhook.platform.common.util.WebhookSignatureUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Uses the worker's TransformationRunner, so the bytes shown are the bytes that would go out. */
@Service
@Slf4j
public class DeliveryDryRunService {

    private final TransformationRepository transformationRepository;
    private final EndpointRepository endpointRepository;
    private final ObjectMapper objectMapper;
    private final EncryptionKeyRegistry encryptionKeyRegistry;
    private final TransformationRunner runner;

    public DeliveryDryRunService(
            TransformationRepository transformationRepository,
            EndpointRepository endpointRepository,
            ObjectMapper objectMapper,
            EncryptionKeyRegistry encryptionKeyRegistry,
            TransformationRunner runner) {
        this.transformationRepository = transformationRepository;
        this.endpointRepository = endpointRepository;
        this.objectMapper = objectMapper;
        this.encryptionKeyRegistry = encryptionKeyRegistry;
        this.runner = runner;
    }

    // The endpoint comes from the body, unconfined to the project; a sibling's once leaked a signature.
    public DeliveryDryRunResponse dryRun(UUID projectId, DeliveryDryRunRequest request) {
        List<String> errors = new ArrayList<>();
        String transformedPayload = null;
        String transformationName = null;
        Integer transformationVersion = null;
        String signature = null;
        String endpointUrl = null;
        long durationMs = 0;
        Integer errorLine = null;
        ScriptTransformException.Reason errorReason = null;
        List<ScriptConsoleLine> console = List.of();
        Map<String, String> requestHeaders = new LinkedHashMap<>();
        Map<String, String> scriptHeaders = new LinkedHashMap<>();

        JsonNode sourceNode;
        try {
            sourceNode = objectMapper.readTree(request.getPayload());
        } catch (Exception e) {
            errors.add("Invalid input JSON: " + e.getMessage());
            return DeliveryDryRunResponse.builder()
                    .success(false)
                    .errors(errors)
                    .console(List.of())
                    .build();
        }

        // A saved transformation wins and brings its own language.
        String source = null;
        TransformationKind kind = request.getKind() == null
                ? TransformationKind.TEMPLATE : request.getKind();

        if (request.getTransformationId() != null) {
            Optional<Transformation> transformOpt =
                    transformationRepository.findByIdAndProjectId(request.getTransformationId(), projectId);
            if (transformOpt.isEmpty()) {
                errors.add("Transformation not found: " + request.getTransformationId());
            } else {
                Transformation t = transformOpt.get();
                if (!t.getEnabled()) {
                    errors.add("Transformation is disabled: " + t.getName());
                } else {
                    source = t.getTemplate();
                    kind = t.getKind();
                    transformationName = t.getName();
                    transformationVersion = t.getVersion();
                }
            }
        } else if (request.getPayloadTemplate() != null && !request.getPayloadTemplate().isBlank()) {
            source = request.getPayloadTemplate();
        }

        // Read-only to the script: choosing the address would bypass the SSRF checks.
        Endpoint endpoint = null;
        if (request.getEndpointId() != null) {
            endpoint = endpointRepository.findById(request.getEndpointId())
                    // "Not found", so the dry-run cannot enumerate endpoint ids.
                    .filter(e -> projectId.equals(e.getProjectId()))
                    .orElse(null);
            if (endpoint == null) {
                errors.add("Endpoint not found: " + request.getEndpointId());
            } else {
                endpointUrl = endpoint.getUrl();
            }
        }

        // No fallback to the raw payload: AttemptRunner forbids that on a real Delivery.
        if (source != null) {
            try {
                TransformationRunner.Result result = runner.run(kind, source, TransformRequest.builder()
                        .payload(request.getPayload())
                        .eventType(request.getEventType())
                        .eventId("evt_dryrun")
                        .timestamp(Instant.now())
                        .direction("OUTGOING")
                        .url(endpointUrl)
                        // A dry-run names no Subscription, so the script sees the caller's headers.
                        .headers(callerHeaders(request.getCustomHeaders()))
                        .build());

                durationMs = result.durationMs();
                console = result.console();

                if (result.cancelled()) {
                    return DeliveryDryRunResponse.builder()
                            .success(errors.isEmpty())
                            .errors(errors)
                            .cancelled(true)
                            .cancelReason(result.cancelReason())
                            .durationMs(durationMs)
                            .console(consoleDto(console))
                            .transformationKind(kind)
                            .transformationName(transformationName)
                            .transformationVersion(transformationVersion)
                            .endpointUrl(endpointUrl)
                            .build();
                }

                scriptHeaders.putAll(result.headers());
                transformedPayload = pretty(result.payload());

            } catch (ScriptTransformException e) {
                String where = e.line() > 0 ? " (line " + e.line() + ")" : "";
                errors.add(e.reason() + where + ": " + e.getMessage());
                console = e.console();
                errorLine = e.line() > 0 ? e.line() : null;
                errorReason = e.reason();
            }
        } else {
            try {
                transformedPayload = objectMapper.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(sourceNode);
            } catch (Exception e) {
                transformedPayload = request.getPayload();
            }
        }

        long timestamp = System.currentTimeMillis();
        requestHeaders.put("Content-Type", "application/json");
        requestHeaders.put("User-Agent", "WebhookPlatform/1.0");
        requestHeaders.put("X-Timestamp", String.valueOf(timestamp));

        if (request.getEventType() != null) {
            requestHeaders.put("X-Event-Type", request.getEventType());
        }

        if (endpoint != null) {
            try {
                // The registry resolves the key version across a rotation, as real delivery does.
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

        // Script headers go over the computed ones and under the caller's own, as on a real Delivery.
        requestHeaders.putAll(scriptHeaders);

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
                .transformationKind(kind)
                .console(consoleDto(console))
                .cancelled(false)
                .durationMs(durationMs)
                .errorLine(errorLine)
                .errorReason(errorReason)
                .build();
    }

    private Map<String, String> callerHeaders(String customHeadersJson) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (customHeadersJson == null || customHeadersJson.isBlank()) {
            return headers;
        }
        try {
            JsonNode parsed = objectMapper.readTree(customHeadersJson);
            if (parsed.isObject()) {
                parsed.properties().forEach(entry -> headers.put(entry.getKey(), entry.getValue().asText()));
            }
        } catch (Exception e) {
            // Reported to the caller further down, where the headers are merged for real.
            log.debug("Custom headers are not JSON, so the script sees none: {}", e.getMessage());
        }
        return headers;
    }

    private List<TransformPreviewResponse.ConsoleLine> consoleDto(List<ScriptConsoleLine> lines) {
        return lines.stream()
                .map(line -> TransformPreviewResponse.ConsoleLine.builder()
                        .level(line.level())
                        .message(line.message())
                        .build())
                .toList();
    }

    private String pretty(JsonNode node) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (Exception e) {
            return node == null ? null : node.toString();
        }
    }
}

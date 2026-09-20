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

/**
 * Builds the exact request a real Delivery would make — body, headers and signature — and does
 * not send it.
 *
 * <p>The transformation half goes through {@link TransformationRunner}, which is the same engine
 * the worker runs. This endpoint's whole value is that the bytes it shows are the bytes that
 * would go out; a second implementation here would be a lie with a green tick on it.
 */
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

    /**
     * @param projectId the project in the request path, which the caller has already been shown to
     *                  have access to. It has to be threaded down here because the endpoint this
     *                  signs for arrives in the request <em>body</em>: {@code @TenantId} confines
     *                  the lookup to the organization and {@code ScopeEnforcementInterceptor}
     *                  confines the path variable, and an id in the body is outside both. An
     *                  organization with two projects is the ordinary case, and this is the one
     *                  read path that hands back a working signature rather than a description of
     *                  one — so a sibling project's endpoint leaked both a forgeable
     *                  {@code X-Signature} and the URL to aim it at.
     */
    public DeliveryDryRunResponse dryRun(UUID projectId, DeliveryDryRunRequest request) {
        List<String> errors = new ArrayList<>();
        String transformedPayload = null;
        String transformationName = null;
        Integer transformationVersion = null;
        String signature = null;
        String endpointUrl = null;
        long durationMs = 0;
        List<ScriptConsoleLine> console = List.of();
        Map<String, String> requestHeaders = new LinkedHashMap<>();
        Map<String, String> scriptHeaders = new LinkedHashMap<>();

        // 1. Parse input payload
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

        // 2. Resolve the transformation. A saved one wins, and brings its own language with it.
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

        // The endpoint is resolved before the transform because a script is shown the URL it is
        // being run for. Read-only: a transformation that could choose the address would be past
        // the SSRF checks a real attempt makes before it gets anywhere near here.
        Endpoint endpoint = null;
        if (request.getEndpointId() != null) {
            endpoint = endpointRepository.findById(request.getEndpointId())
                    // Deliberately folded into "not found": that is the answer @TenantId already
                    // gives for another organization's row, and distinguishing the two here would
                    // make the dry-run a way to enumerate endpoint ids.
                    .filter(e -> projectId.equals(e.getProjectId()))
                    .orElse(null);
            if (endpoint == null) {
                errors.add("Endpoint not found: " + request.getEndpointId());
            } else {
                endpointUrl = endpoint.getUrl();
            }
        }

        // 3. Transform. A failure here fails the dry-run rather than falling back to the raw
        //    payload: this endpoint exists to show the bytes that would go out, and the fallback
        //    is the one AttemptRunner's invariant 4 forbids on a real Delivery.
        if (source != null) {
            try {
                TransformationRunner.Result result = runner.run(kind, source, TransformRequest.builder()
                        .payload(request.getPayload())
                        .eventType(request.getEventType())
                        .eventId("evt_dryrun")
                        .timestamp(Instant.now())
                        .direction("OUTGOING")
                        .url(endpointUrl)
                        .headers(Map.of())
                        .build());

                durationMs = result.durationMs();
                console = result.console();

                if (result.cancelled()) {
                    // Nothing would be sent, so there is no body to sign and no headers to show.
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
            }
        } else {
            try {
                transformedPayload = objectMapper.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(sourceNode);
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

        // 5. Compute HMAC signature if an endpoint was named
        if (endpoint != null) {
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

        // 6. Whatever the script set, over the computed headers and under the caller's own —
        //    which stay the last word here exactly as they are on a real Delivery.
        requestHeaders.putAll(scriptHeaders);

        // 7. Merge custom headers
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
                .build();
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

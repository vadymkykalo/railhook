package com.webhook.platform.api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.domain.entity.Transformation;
import com.webhook.platform.api.domain.repository.TransformationRepository;
import com.webhook.platform.api.dto.TransformPreviewRequest;
import com.webhook.platform.api.dto.TransformPreviewResponse;
import com.webhook.platform.api.service.transform.TransformationRunner;
import com.webhook.platform.common.transform.ScriptConsoleLine;
import com.webhook.platform.common.transform.ScriptTransformException;
import com.webhook.platform.common.transform.TransformRequest;
import com.webhook.platform.common.transform.TransformationKind;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The Transform Studio's run button.
 *
 * <p>Nothing here is a second implementation of anything: a script goes through the same
 * {@code JavaScriptTransformEngine} the worker uses, under the same limits, with the same
 * sandbox. What a person sees here is what the endpoint gets, which is the only reason a
 * preview is worth having.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TransformPreviewService {

    private final ObjectMapper objectMapper;
    private final TransformationRepository transformationRepository;
    private final TransformationRunner runner;

    public TransformPreviewResponse preview(UUID projectId, TransformPreviewRequest request) {
        List<String> errors = new ArrayList<>();

        JsonNode root;
        try {
            root = objectMapper.readTree(request.getInputPayload());
        } catch (Exception e) {
            return failed(List.of("Invalid input JSON: " + e.getMessage()), null);
        }

        Map<String, String> headers = new LinkedHashMap<>();
        if (request.getCustomHeaders() != null && !request.getCustomHeaders().isBlank()) {
            try {
                JsonNode parsed = objectMapper.readTree(request.getCustomHeaders());
                if (!parsed.isObject()) {
                    errors.add("Custom headers must be a JSON object");
                } else {
                    parsed.properties().forEach(entry ->
                            headers.put(entry.getKey(), entry.getValue().asText()));
                }
            } catch (Exception e) {
                errors.add("Invalid custom headers JSON: " + e.getMessage());
            }
        }

        // A saved transformation wins over an unsaved one, and brings its own language with it.
        String source = null;
        TransformationKind kind = request.getKind() == null
                ? TransformationKind.TEMPLATE : request.getKind();

        if (request.getTransformationId() != null) {
            Transformation transformation = transformationRepository
                    .findByIdAndProjectId(request.getTransformationId(), projectId)
                    .orElse(null);
            if (transformation == null) {
                errors.add("Transformation not found: " + request.getTransformationId());
            } else {
                source = transformation.getTemplate();
                kind = transformation.getKind();
            }
        } else if (request.getTemplate() != null && !request.getTemplate().isBlank()) {
            source = request.getTemplate();
        }

        if (!errors.isEmpty()) {
            return failed(errors, kind);
        }

        if (source == null) {
            return previewWithoutATransformation(request, root, headers, kind);
        }

        try {
            TransformationRunner.Result result = runner.run(kind, source, TransformRequest.builder()
                    .payload(request.getInputPayload())
                    .eventType(orDefault(request.getEventType(), "event.preview"))
                    .eventId(orDefault(request.getEventId(), "evt_preview"))
                    .timestamp(Instant.now())
                    .direction("OUTGOING")
                    .url(orDefault(request.getUrl(), "https://example.com/webhooks"))
                    .headers(headers)
                    .build());

            headers.putAll(result.headers());

            return TransformPreviewResponse.builder()
                    .outputPayload(result.cancelled() ? null : pretty(result.payload()))
                    .outputHeaders(headers.isEmpty() ? null : pretty(objectMapper.valueToTree(headers)))
                    .success(true)
                    .errors(List.of())
                    .kind(kind)
                    .console(console(result.console()))
                    .consoleTruncated(result.consoleTruncated())
                    .cancelled(result.cancelled())
                    .cancelReason(result.cancelReason())
                    .durationMs(result.durationMs())
                    .build();

        } catch (ScriptTransformException e) {
            return TransformPreviewResponse.builder()
                    .success(false)
                    .errors(List.of(describe(e)))
                    .kind(kind)
                    .console(console(e.console()))
                    .errorLine(e.line() > 0 ? e.line() : null)
                    .errorReason(e.reason())
                    .build();
        }
    }

    /**
     * The two shapes that are not a transformation at all: a bare JSONPath to pull one branch
     * out, and nothing, which is a formatted echo. Both predate saved transformations and both
     * are still what the Studio does the first time somebody opens it.
     */
    private TransformPreviewResponse previewWithoutATransformation(TransformPreviewRequest request,
            JsonNode root, Map<String, String> headers, TransformationKind kind) {
        String expression = request.getTransformExpression();
        if (expression == null || expression.isBlank()) {
            return TransformPreviewResponse.builder()
                    .outputPayload(pretty(root))
                    .outputHeaders(headers.isEmpty() ? null : pretty(objectMapper.valueToTree(headers)))
                    .success(true)
                    .errors(List.of())
                    .kind(kind)
                    .console(List.of())
                    .build();
        }

        String pointer = expression.trim();
        if (pointer.startsWith("$.")) {
            pointer = "/" + pointer.substring(2).replace(".", "/");
        } else if (pointer.startsWith("$")) {
            pointer = "";
        } else if (!pointer.startsWith("/")) {
            pointer = "/" + pointer.replace(".", "/");
        }

        JsonNode result = pointer.isEmpty() ? root : root.at(pointer);
        if (result.isMissingNode()) {
            return TransformPreviewResponse.builder()
                    .outputPayload("null")
                    .success(false)
                    .errors(List.of("Expression matched no data: " + expression))
                    .kind(kind)
                    .console(List.of())
                    .build();
        }
        return TransformPreviewResponse.builder()
                .outputPayload(pretty(result))
                .outputHeaders(headers.isEmpty() ? null : pretty(objectMapper.valueToTree(headers)))
                .success(true)
                .errors(List.of())
                .kind(kind)
                .console(List.of())
                .build();
    }

    /**
     * What the author is told. The reason comes first because it decides what to do about it: a
     * TIMEOUT is a loop to find, a CONTRACT is a return statement to fix, and they read nothing
     * alike.
     */
    private String describe(ScriptTransformException e) {
        String where = e.line() > 0 ? " (line " + e.line() + ")" : "";
        return e.reason() + where + ": " + e.getMessage();
    }

    private List<TransformPreviewResponse.ConsoleLine> console(List<ScriptConsoleLine> lines) {
        return lines.stream()
                .map(line -> TransformPreviewResponse.ConsoleLine.builder()
                        .level(line.level())
                        .message(line.message())
                        .build())
                .toList();
    }

    private TransformPreviewResponse failed(List<String> errors, TransformationKind kind) {
        return TransformPreviewResponse.builder()
                .success(false)
                .errors(errors)
                .kind(kind)
                .console(List.of())
                .build();
    }

    private String pretty(JsonNode node) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (Exception e) {
            return node == null ? null : node.toString();
        }
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}

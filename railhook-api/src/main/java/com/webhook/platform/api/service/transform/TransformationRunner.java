package com.webhook.platform.api.service.transform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.common.transform.JavaScriptTransformEngine;
import com.webhook.platform.common.transform.ScriptConsoleLine;
import com.webhook.platform.common.transform.ScriptTransformException;
import com.webhook.platform.common.transform.TransformOutcome;
import com.webhook.platform.common.transform.TransformRequest;
import com.webhook.platform.common.transform.TransformationKind;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** Same engine as the worker, so a preview cannot disagree with a real delivery. */
@Component
@RequiredArgsConstructor
public class TransformationRunner {

    private final TemplateTransformer templateTransformer;
    private final JavaScriptTransformEngine scriptEngine;
    private final ObjectMapper objectMapper;

    @Builder
    public record Result(
            JsonNode payload,
            Map<String, String> headers,
            boolean cancelled,
            String cancelReason,
            List<ScriptConsoleLine> console,
            boolean consoleTruncated,
            long durationMs) {
    }

    // A template throws only when it is not JSON; an unmatched path is a missing optional field.
    public Result run(TransformationKind kind, String source, TransformRequest context) {
        if (kind == TransformationKind.JAVASCRIPT) {
            TransformOutcome outcome = scriptEngine.run(source, context);
            return Result.builder()
                    .payload(outcome.cancelled() ? null : parse(outcome.payload()))
                    .headers(outcome.headers())
                    .cancelled(outcome.cancelled())
                    .cancelReason(outcome.cancelReason())
                    .console(outcome.console())
                    .consoleTruncated(outcome.consoleTruncated())
                    .durationMs(outcome.durationMs())
                    .build();
        }

        long startedAt = System.nanoTime();
        try {
            JsonNode applied = templateTransformer.apply(source, parse(context.payload()));
            return Result.builder()
                    .payload(applied)
                    .headers(Map.of())
                    .console(List.of())
                    .durationMs((System.nanoTime() - startedAt) / 1_000_000)
                    .build();
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new ScriptTransformException(ScriptTransformException.Reason.SYNTAX,
                    "The template is not valid JSON: " + e.getOriginalMessage());
        }
    }

    private JsonNode parse(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new ScriptTransformException(ScriptTransformException.Reason.RUNTIME,
                    "Not valid JSON: " + e.getMessage());
        }
    }
}

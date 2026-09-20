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

/**
 * Applies a transformation of either language, so that nothing in the api has to know which one
 * it is holding.
 *
 * <p>Both read paths that show a person what a transformation does — the Transform Studio's
 * preview and the delivery dry-run — go through here, and the JavaScript half is
 * {@link JavaScriptTransformEngine}, the same instance the worker runs a real Delivery through.
 * That is the point: a preview that disagreed with the delivery would be worse than no preview.
 */
@Component
@RequiredArgsConstructor
public class TransformationRunner {

    private final TemplateTransformer templateTransformer;
    private final JavaScriptTransformEngine scriptEngine;
    private final ObjectMapper objectMapper;

    /** What either language produced, in the shape the JavaScript one needs. */
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

    /**
     * @param source the template or the script, already resolved from wherever it was stored
     * @param context the Event and delivery context a script sees; ignored by a template, which
     *                has no way to reach anything but the payload
     * @throws ScriptTransformException when a script fails, for any reason. A template is
     *                                  deliberately more forgiving — a path that matches nothing
     *                                  is a missing optional field, not an error — so it throws
     *                                  only when the template is not JSON.
     */
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

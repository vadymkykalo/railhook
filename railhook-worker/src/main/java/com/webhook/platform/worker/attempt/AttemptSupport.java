package com.webhook.platform.worker.attempt;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * What both Attempt Stores do identically. Copies of these drifted apart once already, which is
 * how the two directions ended up fencing a Claim on different rules.
 */
@Slf4j
final class AttemptSupport {

    private static final int DEFAULT_TIMEOUT_SECONDS = 30;
    private static final int MIN_TIMEOUT_SECONDS = 1;
    private static final int MAX_TIMEOUT_SECONDS = 60;

    private AttemptSupport() {
    }

    /**
     * Does a Claim still own the row it is about to write?
     *
     * <p>The status cannot answer it: a claim swept as abandoned and re-claimed by another
     * attempt leaves the row in the same state, for somebody else. An unfenced Claim — one whose
     * retry message predates the token — matches only a row that carries no token either.
     */
    static boolean fenceMatches(UUID rowToken, UUID claimFence) {
        return rowToken == null ? claimFence == null : rowToken.equals(claimFence);
    }

    /** Host, Content-Length and Transfer-Encoding belong to the transport, not to the caller. */
    static void addCustomHeaders(WebClient.RequestBodySpec request, String customHeadersJson,
            ObjectMapper objectMapper) {
        Map<String, String> collected = new LinkedHashMap<>();
        collectCustomHeaders(collected, customHeadersJson, objectMapper);
        collected.forEach(request::header);
    }

    /**
     * The same selection, into a map the caller still owns — a store that has to record what it
     * sent needs the headers before they disappear into the request builder.
     */
    @SuppressWarnings("unchecked")
    static void collectCustomHeaders(Map<String, String> into, String customHeadersJson,
            ObjectMapper objectMapper) {
        if (customHeadersJson == null || customHeadersJson.isBlank()) {
            return;
        }
        try {
            Map<String, String> headers = objectMapper.readValue(customHeadersJson, Map.class);
            headers.forEach((key, value) -> {
                if (key != null && value != null && !key.isBlank()) {
                    String lower = key.toLowerCase();
                    if (!lower.equals("host") && !lower.equals("content-length")
                            && !lower.equals("transfer-encoding")) {
                        into.put(key, value);
                    }
                }
            });
        } catch (Exception e) {
            log.warn("Failed to parse custom headers: {}", e.getMessage());
        }
    }

    static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "\n...[truncated]";
    }

    static int clampTimeout(Integer timeoutSeconds) {
        if (timeoutSeconds == null) {
            return DEFAULT_TIMEOUT_SECONDS;
        }
        return Math.max(MIN_TIMEOUT_SECONDS, Math.min(MAX_TIMEOUT_SECONDS, timeoutSeconds));
    }
}

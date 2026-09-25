package com.webhook.platform.worker.attempt;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.UUID;

/** Shared by both stores. Separate copies once drifted and fenced Claims by different rules. */
@Slf4j
final class AttemptSupport {

    private static final int DEFAULT_TIMEOUT_SECONDS = 30;
    private static final int MIN_TIMEOUT_SECONDS = 1;
    private static final int MAX_TIMEOUT_SECONDS = 60;

    private AttemptSupport() {
    }

    /** An unfenced Claim (from an older retry message) matches only a row with no token. */
    static boolean fenceMatches(UUID rowToken, UUID claimFence) {
        return rowToken == null ? claimFence == null : rowToken.equals(claimFence);
    }

    /** Host, Content-Length and Transfer-Encoding belong to the transport and are skipped. */
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

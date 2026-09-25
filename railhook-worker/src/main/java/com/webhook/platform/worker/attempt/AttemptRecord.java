package com.webhook.platform.worker.attempt;

/** Untruncated; each store decides how much to keep. {@code statusCode} is null with no response. */
public record AttemptRecord(
        Integer statusCode,
        String responseBody,
        String responseHeaders,
        String requestHeaders,
        String requestBody,
        String errorMessage,
        int durationMs) {
}

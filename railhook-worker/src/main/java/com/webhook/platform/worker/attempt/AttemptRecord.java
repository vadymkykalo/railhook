package com.webhook.platform.worker.attempt;

/**
 * What one Attempt did, for the store to persist however its direction records Attempts —
 * a row appended to {@code delivery_attempts} for Outgoing, fields written onto the
 * {@code incoming_forward_attempts} row itself for Incoming.
 *
 * <p>Truncation is left to the store: the two directions already keep different amounts of
 * response body, and that is a storage decision rather than a policy one.
 *
 * @param statusCode      HTTP status, or null when the request never produced a response
 * @param responseBody    may be null
 * @param responseHeaders serialised and sanitised by the Runner, may be null
 * @param requestHeaders  as supplied by {@link RequestSpec#recordedHeaders}, may be null
 * @param requestBody     the transformed body actually sent, may be null when the failure
 *                        happened before there was one
 * @param errorMessage    null when the Attempt produced a response, however unwelcome
 * @param durationMs      wall time from the start of the Attempt
 */
public record AttemptRecord(
        Integer statusCode,
        String responseBody,
        String responseHeaders,
        String requestHeaders,
        String requestBody,
        String errorMessage,
        int durationMs) {
}

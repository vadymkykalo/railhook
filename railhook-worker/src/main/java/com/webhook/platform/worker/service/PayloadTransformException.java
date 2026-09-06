package com.webhook.platform.worker.service;

/**
 * Thrown when a *configured* payload transformation cannot be applied — a broken
 * template, invalid source JSON, or a referenced {@code Transformation} row that is
 * missing or disabled.
 *
 * <p>Customers configure transformations specifically to strip PII before a payload
 * leaves the platform. A failure here must never be swallowed into "send the raw
 * payload instead" — callers are expected to catch this and fail the
 * delivery/forward attempt as retryable, the same way an HTTP-level failure would,
 * so it flows through the normal retry ladder and eventually DLQs rather than
 * silently leaking the untransformed payload.
 *
 * <p>This is distinct from "no transformation configured", which is not an error —
 * that case must keep sending the payload as-is.
 */
public class PayloadTransformException extends RuntimeException {

    public PayloadTransformException(String message) {
        super(message);
    }

    public PayloadTransformException(String message, Throwable cause) {
        super(message, cause);
    }
}

package com.webhook.platform.worker.service;

/**
 * A configured transformation could not be applied. Callers fail the attempt as retryable and
 * never fall back to the raw payload, which the transformation is often there to strip of PII.
 */
public class PayloadTransformException extends RuntimeException {

    public PayloadTransformException(String message) {
        super(message);
    }

    public PayloadTransformException(String message, Throwable cause) {
        super(message, cause);
    }
}

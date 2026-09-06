package com.webhook.platform.worker.service;

/**
 * Thrown when the worker rejects a delivery because graceful shutdown is in progress.
 * The Kafka consumer must nack the message so it will be redelivered.
 */
public class ShutdownRejectedException extends RuntimeException {

    public ShutdownRejectedException(String message) {
        super(message);
    }
}

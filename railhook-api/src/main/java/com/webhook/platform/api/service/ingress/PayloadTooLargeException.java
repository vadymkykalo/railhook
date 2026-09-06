package com.webhook.platform.api.service.ingress;

public class PayloadTooLargeException extends RuntimeException {
    public PayloadTooLargeException(String message) { super(message); }
}

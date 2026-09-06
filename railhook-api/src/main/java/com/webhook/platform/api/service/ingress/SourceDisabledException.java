package com.webhook.platform.api.service.ingress;

public class SourceDisabledException extends RuntimeException {
    public SourceDisabledException(String message) { super(message); }
}

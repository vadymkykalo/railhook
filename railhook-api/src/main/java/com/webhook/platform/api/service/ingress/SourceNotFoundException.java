package com.webhook.platform.api.service.ingress;

public class SourceNotFoundException extends RuntimeException {
    public SourceNotFoundException(String message) { super(message); }
}

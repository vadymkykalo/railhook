package com.webhook.platform.api.service.ingress;

public class SignatureVerificationFailedException extends RuntimeException {

    public SignatureVerificationFailedException(String message) {
        super(message);
    }
}

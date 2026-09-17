package com.webhook.platform.api.service.ingress;

public class OrganizationSuspendedException extends RuntimeException {
    public OrganizationSuspendedException(String message) { super(message); }
}

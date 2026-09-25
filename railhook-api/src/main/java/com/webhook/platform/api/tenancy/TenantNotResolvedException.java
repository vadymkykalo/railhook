package com.webhook.platform.api.tenancy;

/**
 * Not an IllegalStateException, which the exception handler maps to 422: a missing tenant scope
 * is always a server bug.
 */
public class TenantNotResolvedException extends RuntimeException {

    public TenantNotResolvedException(String message) {
        super(message);
    }
}

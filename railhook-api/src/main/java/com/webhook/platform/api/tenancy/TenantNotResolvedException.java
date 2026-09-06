package com.webhook.platform.api.tenancy;

/**
 * Thrown when a Hibernate session is opened on a thread that has entered no tenant scope.
 *
 * <p>Deliberately a plain {@code RuntimeException} rather than an {@code IllegalStateException}:
 * {@code GlobalExceptionHandler} maps the latter to 422, and this is never a client's fault and
 * never a 4xx. It means a code path reached the database without saying whose data it is looking
 * at — either a request path that the tenant filter does not cover, or background work that
 * forgot {@link TenantContext#runAsSystem}. Both are bugs, and a 500 with this message names the
 * fix.
 */
public class TenantNotResolvedException extends RuntimeException {

    public TenantNotResolvedException(String message) {
        super(message);
    }
}

package com.webhook.platform.api.security;

import com.webhook.platform.api.domain.enums.ApiKeyScope;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the minimum API key scope required to access a controller method.
 * When an API key authenticates a request, the {@link ScopeEnforcementInterceptor}
 * verifies that the key's scope satisfies this requirement.
 *
 * <p>For JWT-authenticated users this annotation is a no-op (JWT users have
 * full access governed by their {@link com.webhook.platform.api.domain.enums.MembershipRole}).</p>
 *
 * <p>Methods without this annotation default to <b>deny</b> for API keys
 * (unless the class-level annotation permits access).</p>
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireScope {
    ApiKeyScope value();
}

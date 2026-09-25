package com.webhook.platform.api.security;

import com.webhook.platform.api.domain.enums.ApiKeyScope;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Ignored for JWT callers. An API key is denied on a method with no annotation at method or
 * class level.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireScope {
    ApiKeyScope value();
}

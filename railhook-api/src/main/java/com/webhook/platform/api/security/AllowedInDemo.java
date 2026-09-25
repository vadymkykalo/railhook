package com.webhook.platform.api.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Lets the public demo call a handler that is not GET, HEAD or OPTIONS, and lifts
 * {@link RequireAccess} for the demo on that handler. Only for handlers that change nothing
 * belonging to the demo. A handler whose level exists to protect a capability must hand the demo
 * an answer without it.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface AllowedInDemo {

    String reason();
}

package com.webhook.platform.api.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A state-changing handler the public demo may still call.
 *
 * <p>{@link ScopeEnforcementInterceptor} refuses every request from a demo session whose method
 * is not GET, HEAD or OPTIONS. This is the only way past that, and it is meant for handlers that
 * change nothing belonging to the demo: ending the demo session itself, opening a new one, and the
 * public site's anonymous forms, which a visitor still holding a demo token must be able to use.
 *
 * <p>{@code DemoSessionAllowListTest} freezes the set, so adding one is a reviewed decision.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface AllowedInDemo {

    /** Why the demo may call this — what it changes, and why that is nobody's data. */
    String reason();
}

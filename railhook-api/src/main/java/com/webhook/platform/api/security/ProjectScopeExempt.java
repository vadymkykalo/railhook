package com.webhook.platform.api.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Opt-out from the project-tenancy check {@link ScopeEnforcementInterceptor} applies to every
 * route carrying a {@code {projectId}} path variable, which otherwise confines an API key to
 * its own project unconditionally.
 *
 * <p>{@link #reason()} is mandatory so an exemption reads as a decision in a diff. Nothing in
 * production needs one today; the mechanism exists so a real exception has a sanctioned way
 * out rather than reviving the opt-in check a third of routes never called.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface ProjectScopeExempt {

    String reason();
}

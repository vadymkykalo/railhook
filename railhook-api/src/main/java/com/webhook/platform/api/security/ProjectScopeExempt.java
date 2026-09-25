package com.webhook.platform.api.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Opts a route out of confining an API key to the {@code {projectId}} in its path. The reason is
 * mandatory so an exemption reads as a decision in review.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface ProjectScopeExempt {

    String reason();
}

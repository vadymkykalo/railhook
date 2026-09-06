package com.webhook.platform.api.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The {@link AccessLevel} a handler requires, enforced by {@link ScopeEnforcementInterceptor}
 * before the handler runs.
 *
 * <p>This replaces reading a handler's body to find out who may call it: three handlers once
 * shipped reachable by a VIEWER JWT because the guard was an imperative call nobody had written.
 * Method-level wins over class-level, and
 * {@code MutatingHandlerAccessDeclarationTest} fails the build on a state-changing handler that
 * declares neither this nor a documented exemption.
 *
 * <p>The {@code auth.requireWriteAccess()} calls in handler bodies are redundant as checks —
 * the interceptor calls the same {@link RbacUtil} and cannot be forgotten — but deleting 79 of
 * them on the authorization path buys only tidiness. Copy the call from the neighbours when
 * writing a new handler; do not start a campaign in either direction.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.METHOD, ElementType.TYPE })
public @interface RequireAccess {

    AccessLevel value();
}

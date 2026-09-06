package com.webhook.platform.api.security;

import java.util.Optional;
import java.util.UUID;

/**
 * The one question {@link ScopeEnforcementInterceptor} asks about suspension: is this
 * organization stopped, and what was it told.
 *
 * <p>An interface rather than the service itself, because this is the whole of what the
 * interceptor needs and the service behind it owns a cache, a repository and a transaction. The
 * seam is here, at the consumer, so the enforcement can be tested for what it does — refuse a
 * write, let a read through — without standing up the thing that answers.
 *
 * @return the operator's stated reason when suspended, empty when not
 */
@FunctionalInterface
public interface SuspensionCheck {

    Optional<String> suspensionReason(UUID organizationId);
}

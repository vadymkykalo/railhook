package com.webhook.platform.api.security;

import java.util.Optional;
import java.util.UUID;

/**
 * Lets the interceptor's enforcement be tested without the service behind it, which owns a cache,
 * a repository and a transaction.
 */
@FunctionalInterface
public interface SuspensionCheck {

    /** The operator's stated reason when suspended, empty when not. */
    Optional<String> suspensionReason(UUID organizationId);
}

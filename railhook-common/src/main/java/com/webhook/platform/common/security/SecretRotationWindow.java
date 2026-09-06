package com.webhook.platform.common.security;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Whether an endpoint's retired signing secret is still worth signing with.
 *
 * <p>A rotation opens a window: for {@code graceHours} after {@code rotatedAt} the worker
 * signs each delivery with both the new secret and the one being retired, so a receiver who
 * has not deployed the new one yet keeps verifying. Outside the window only the current
 * secret is used.
 *
 * <p>A function of a timestamp rather than a method on the entity, because there are two
 * {@code Endpoint} entities and this rule should not have two copies.
 */
public final class SecretRotationWindow {

    private SecretRotationWindow() {
    }

    /** A null or non-positive {@code graceHours} closes the window: that is how an endpoint
     * opts out of dual-signing. */
    public static boolean isOpen(Instant rotatedAt, Integer graceHours, Instant now) {
        if (rotatedAt == null || graceHours == null || graceHours <= 0) {
            return false;
        }
        // Inclusive: a receiver reading "24 hours" has until the end of the 24th hour, and one
        // extra signature is the harmless direction to err in.
        return !now.isAfter(rotatedAt.plus(graceHours, ChronoUnit.HOURS));
    }
}

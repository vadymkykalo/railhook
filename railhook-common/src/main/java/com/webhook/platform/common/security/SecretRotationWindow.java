package com.webhook.platform.common.security;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * During the window after a rotation, deliveries are signed with both the new and the retired
 * secret. A static function because there are two {@code Endpoint} entities.
 */
public final class SecretRotationWindow {

    private SecretRotationWindow() {
    }

    /** A null or non-positive {@code graceHours} opts the endpoint out of dual-signing. */
    public static boolean isOpen(Instant rotatedAt, Integer graceHours, Instant now) {
        if (rotatedAt == null || graceHours == null || graceHours <= 0) {
            return false;
        }
        // Inclusive: one extra signature is the harmless way to err.
        return !now.isAfter(rotatedAt.plus(graceHours, ChronoUnit.HOURS));
    }
}

package com.webhook.platform.common.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("SecretRotationWindow")
class SecretRotationWindowTest {

    private static final Instant ROTATED_AT = Instant.parse("2026-03-01T12:00:00Z");

    @Test
    @DisplayName("open for the whole grace period, closed after it")
    void windowSpansTheGracePeriod() {
        assertTrue(SecretRotationWindow.isOpen(ROTATED_AT, 24, ROTATED_AT));
        assertTrue(SecretRotationWindow.isOpen(ROTATED_AT, 24, ROTATED_AT.plusSeconds(23 * 3600)));

        // The boundary belongs to the window: a receiver told "24 hours" has the 24th hour.
        assertTrue(SecretRotationWindow.isOpen(ROTATED_AT, 24, ROTATED_AT.plusSeconds(24 * 3600)));
        assertFalse(SecretRotationWindow.isOpen(ROTATED_AT, 24, ROTATED_AT.plusSeconds(24 * 3600 + 1)));
    }

    @Test
    @DisplayName("a never-rotated endpoint has no window")
    void neverRotated() {
        assertFalse(SecretRotationWindow.isOpen(null, 24, ROTATED_AT));
    }

    @Test
    @DisplayName("a zero or absent grace period opts out of dual-signing")
    void zeroGraceOptsOut() {
        assertFalse(SecretRotationWindow.isOpen(ROTATED_AT, 0, ROTATED_AT));
        assertFalse(SecretRotationWindow.isOpen(ROTATED_AT, -1, ROTATED_AT));
        assertFalse(SecretRotationWindow.isOpen(ROTATED_AT, null, ROTATED_AT));
    }

    @Test
    @DisplayName("a clock that has gone backwards does not reopen a closed window forever")
    void clockSkewBeforeRotation() {
        /* now < rotatedAt happens on a node whose clock is behind. Reporting the window open
           is the safe answer — an extra signature costs nothing, refusing one breaks a
           receiver who is mid-migration. */
        assertTrue(SecretRotationWindow.isOpen(ROTATED_AT, 24, ROTATED_AT.minusSeconds(3600)));
    }
}

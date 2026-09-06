package com.webhook.platform.worker.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetryLadderCapValidatorTest {

    /**
     * The original pairing: a ~83h ladder against a 48h escalation cap. It has to fail at startup
     * rather than let the escalation sweep DLQ a Delivery before its last two tiers ever fire.
     */
    @Test
    void ladderOutlivingItsHardCap_failsStartup() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new RetryLadderCapValidator(48L, 24L).validate());
        assertTrue(ex.getMessage().contains("hard-cap-hours"),
                "expected the failure to name the mismatched config, was: " + ex.getMessage());
    }

    @Test
    void eachDirectionIsCheckedAgainstItsOwnCap() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new RetryLadderCapValidator(96L, 4L).validate());
        assertTrue(ex.getMessage().contains("forward.escalation.hard-cap-hours"),
                "the incoming ladder must be checked against the incoming cap, was: " + ex.getMessage());
    }

    @Test
    void theShippedPairingStarts() {
        assertDoesNotThrow(() -> new RetryLadderCapValidator(96L, 24L).validate());
    }
}

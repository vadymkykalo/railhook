package com.webhook.platform.common.retry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the retry-ladder arithmetic and, more importantly, the refusal to guess.
 *
 * <p>Replaces the ladder half of the worker's {@code RetryPolicyTest}, which asserted the
 * opposite contract: that a malformed ladder falls back to a hardcoded array. That fallback
 * is the behaviour this type exists to remove.
 */
class RetryLadderTest {

    @Nested
    @DisplayName("parsing refuses to guess")
    class Parsing {

        @Test
        @DisplayName("a well-formed ladder parses to exactly what was written")
        void parsesExactly() {
            RetryLadder ladder = RetryLadder.parse(" 10 , 20 ,30 ", 3);
            assertEquals(List.of(10L, 20L, 30L), ladder.delaysSeconds());
            assertEquals(3, ladder.maxAttempts());
        }

        @ParameterizedTest
        @ValueSource(strings = { "", "   ", "abc", "10,notanumber,30", "10,,30", "10,20,", "1.5,2" })
        @DisplayName("a malformed ladder throws instead of substituting a default")
        void malformedThrows(String delays) {
            assertThrows(IllegalArgumentException.class, () -> RetryLadder.parse(delays, 3));
        }

        @Test
        @DisplayName("null throws — the row always carries a ladder, so null is a bug not a default")
        void nullThrows() {
            assertThrows(IllegalArgumentException.class, () -> RetryLadder.parse(null, 3));
        }

        @ParameterizedTest
        @ValueSource(strings = { "0", "-60", "60,0,900", "60,-1" })
        @DisplayName("a non-positive tier throws")
        void nonPositiveTierThrows(String delays) {
            assertThrows(IllegalArgumentException.class, () -> RetryLadder.parse(delays, 3));
        }

        @Test
        @DisplayName("a tier beyond 30 days throws rather than overflowing later")
        void oversizeTierThrows() {
            assertThrows(IllegalArgumentException.class,
                    () -> RetryLadder.parse(String.valueOf(RetryLadder.MAX_TIER_SECONDS + 1), 3));
            assertDoesNotThrow(() -> RetryLadder.parse(String.valueOf(RetryLadder.MAX_TIER_SECONDS), 3));
        }

        @Test
        @DisplayName("too many tiers throws")
        void tooManyTiersThrows() {
            String tooMany = "60,".repeat(RetryLadder.MAX_TIERS) + "60";
            assertThrows(IllegalArgumentException.class, () -> RetryLadder.parse(tooMany, 3));
        }

        @ParameterizedTest
        @ValueSource(ints = { 0, -1, RetryLadder.MAX_ATTEMPTS_LIMIT + 1 })
        @DisplayName("maxAttempts outside 1..100 throws")
        void badMaxAttemptsThrows(int maxAttempts) {
            assertThrows(IllegalArgumentException.class, () -> RetryLadder.parse("60,300", maxAttempts));
        }

        @Test
        @DisplayName("the message names the field and the offending tier, so a 400 is actionable")
        void messageIsActionable() {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> RetryLadder.validate("60,oops,900", "retryDelays"));
            assertTrue(e.getMessage().contains("retryDelays"), e.getMessage());
            assertTrue(e.getMessage().contains("tier 2"), e.getMessage());
            assertTrue(e.getMessage().contains("oops"), e.getMessage());
        }
    }

    @Nested
    @DisplayName("tier selection")
    class Tiers {

        private final RetryLadder ladder = RetryLadder.parse("5,15,45", 6);

        @Test
        @DisplayName("attempt N takes tier N")
        void attemptTakesItsTier() {
            assertEquals(5, ladder.baseDelaySeconds(1));
            assertEquals(15, ladder.baseDelaySeconds(2));
            assertEquals(45, ladder.baseDelaySeconds(3));
        }

        @Test
        @DisplayName("attempts past the end of the ladder clamp to its last tier")
        void clampsToLastTier() {
            assertEquals(45, ladder.baseDelaySeconds(4));
            assertEquals(45, ladder.baseDelaySeconds(99));
        }

        @Test
        @DisplayName("attempt numbers are 1-indexed and 0 is rejected")
        void rejectsZerothAttempt() {
            assertThrows(IllegalArgumentException.class, () -> ladder.baseDelaySeconds(0));
        }
    }

    @Nested
    @DisplayName("jitter")
    class Jitter {

        @Test
        @DisplayName("nextRetryAt lands within 50%-150% of the tier")
        void withinFullJitterRange() {
            RetryLadder ladder = RetryLadder.parse("100", 3);
            for (int i = 0; i < 200; i++) {
                Instant before = Instant.now();
                long delay = Duration.between(before, ladder.nextRetryAt(1)).getSeconds();
                assertTrue(delay >= 49 && delay <= 151, "delay out of jitter range: " + delay);
            }
        }

        @Test
        @DisplayName("successive calls differ, so a same-tier burst does not stampede")
        void spreadsTheBurst() {
            RetryLadder ladder = RetryLadder.parse("10000", 3);
            long a = Duration.between(Instant.now(), ladder.nextRetryAt(1)).getSeconds();
            boolean anyDifferent = false;
            for (int i = 0; i < 20 && !anyDifferent; i++) {
                anyDifferent = Duration.between(Instant.now(), ladder.nextRetryAt(1)).getSeconds() != a;
            }
            assertTrue(anyDifferent, "every jittered delay was identical");
        }
    }

    @Nested
    @DisplayName("exhaustion and the hard cap")
    class Exhaustion {

        @Test
        @DisplayName("exhausted once maxAttempts attempts have been made")
        void exhaustion() {
            RetryLadder ladder = RetryLadder.parse("60", 3);
            assertFalse(ladder.isExhausted(1));
            assertFalse(ladder.isExhausted(2));
            assertTrue(ladder.isExhausted(3));
            assertTrue(ladder.isExhausted(4));
        }

        @Test
        @DisplayName("worst case sums every tier at the top of the jitter range")
        void worstCase() {
            // 10 + 20 + 20 (clamped), each at 1.5x = 15 + 30 + 30
            assertEquals(75, RetryLadder.parse("10,20", 3).worstCaseSpanSeconds());
        }

        @Test
        @DisplayName("the shipped outgoing ladder fits inside the shipped 96h cap")
        void outgoingFitsShippedCap() {
            assertDoesNotThrow(() -> RetryLadderDefaults.outgoing()
                    .requireFitsWithin(Duration.ofHours(96).getSeconds(), "outgoing", "cap"));
        }

        @Test
        @DisplayName("the shipped incoming ladder fits too")
        void incomingFitsShippedCap() {
            assertDoesNotThrow(() -> RetryLadderDefaults.incoming()
                    .requireFitsWithin(Duration.ofHours(96).getSeconds(), "incoming", "cap"));
        }

        @Test
        @DisplayName("a cap the ladder overruns fails, naming both sides")
        void overrunNamesBothSides() {
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> RetryLadderDefaults.outgoing()
                            .requireFitsWithin(Duration.ofHours(48).getSeconds(), "outgoing default", "the-cap"));
            assertTrue(e.getMessage().contains("outgoing default"), e.getMessage());
            assertTrue(e.getMessage().contains("the-cap"), e.getMessage());
        }

        @Test
        @DisplayName("a cap exactly equal to the worst case is accepted")
        void exactFitAccepted() {
            long worstCase = RetryLadderDefaults.outgoing().worstCaseSpanSeconds();
            assertDoesNotThrow(() -> RetryLadderDefaults.outgoing()
                    .requireFitsWithin(worstCase, "outgoing", "cap"));
        }
    }

    @Nested
    @DisplayName("the declared defaults")
    class Defaults {

        @Test
        @DisplayName("both directions parse")
        void bothParse() {
            assertEquals(List.of(60L, 300L, 900L, 3600L, 21600L, 86400L),
                    RetryLadderDefaults.outgoing().delaysSeconds());
            assertEquals(List.of(60L, 300L, 900L, 3600L, 21600L),
                    RetryLadderDefaults.incoming().delaysSeconds());
        }

        @Test
        @DisplayName("the two directions differ on purpose — see RetryLadderDefaults")
        void deliberatelyDifferent() {
            assertNotEquals(RetryLadderDefaults.outgoing(), RetryLadderDefaults.incoming());
            assertEquals(7, RetryLadderDefaults.OUTGOING_MAX_ATTEMPTS);
            assertEquals(5, RetryLadderDefaults.INCOMING_MAX_ATTEMPTS);
        }

        @Test
        @DisplayName("equality is by value, not array identity")
        void equalityByValue() {
            assertEquals(RetryLadder.parse("60,300", 3), RetryLadder.parse("60,300", 3));
            assertEquals(RetryLadder.parse("60,300", 3).hashCode(), RetryLadder.parse("60,300", 3).hashCode());
            assertNotEquals(RetryLadder.parse("60,300", 3), RetryLadder.parse("60,300", 4));
        }
    }
}

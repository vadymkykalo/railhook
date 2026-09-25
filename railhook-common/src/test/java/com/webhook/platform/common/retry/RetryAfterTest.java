package com.webhook.platform.common.retry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Retry-After is honoured only for 429/503, only pushes a retry later, and is clamped. */
class RetryAfterTest {

    private static final Instant NOW = Instant.parse("2026-09-20T12:00:00Z");

    private static final Duration MAX = Duration.ofHours(24);

    private Instant ladderNext(long seconds) {
        return NOW.plusSeconds(seconds);
    }

    @Nested
    @DisplayName("only where the header means what we think it means")
    class Applicability {

        @ParameterizedTest
        @ValueSource(ints = { 429, 503 })
        @DisplayName("429 and 503 are honoured")
        void honouredStatuses(int status) {
            Instant result = RetryAfter.nextRetryAt(ladderNext(60), "600", status, NOW, MAX);
            assertEquals(NOW.plusSeconds(600), result);
        }

        @ParameterizedTest
        @ValueSource(ints = { 408, 500, 502, 504, 200, 404 })
        @DisplayName("every other status keeps the Ladder's own delay, header or no header")
        void ignoredStatuses(int status) {
            assertEquals(ladderNext(60),
                    RetryAfter.nextRetryAt(ladderNext(60), "600", status, NOW, MAX));
        }

        @ParameterizedTest
        @ValueSource(strings = { "", "   ", "soon", "-30", "1.5", "NaN", "60s", "not-a-date", "0" })
        @NullSource
        @DisplayName("a missing, zero or unparseable header keeps the Ladder's own delay")
        void unparseableHeader(String header) {
            assertEquals(ladderNext(60), RetryAfter.nextRetryAt(ladderNext(60), header, 429, NOW, MAX));
        }
    }

    @Nested
    @DisplayName("both wire formats")
    class Formats {

        @Test
        @DisplayName("delta-seconds is taken from now")
        void deltaSeconds() {
            assertEquals(NOW.plusSeconds(120),
                    RetryAfter.nextRetryAt(ladderNext(60), "120", 429, NOW, MAX));
        }

        @Test
        @DisplayName("an HTTP-date is taken as the absolute instant it names")
        void httpDate() {
            assertEquals(Instant.parse("2026-09-20T12:30:00Z"),
                    RetryAfter.nextRetryAt(ladderNext(60), "Sun, 20 Sep 2026 12:30:00 GMT", 503, NOW, MAX));
        }

        @Test
        @DisplayName("an HTTP-date already in the past falls back to the Ladder, not to now")
        void httpDateInThePast() {
            assertEquals(ladderNext(60),
                    RetryAfter.nextRetryAt(ladderNext(60), "Sun, 20 Sep 2026 11:00:00 GMT", 503, NOW, MAX));
        }

    }

    @Nested
    @DisplayName("never shorter than the Ladder, never longer than the clamp")
    class Bounds {

        @Test
        @DisplayName("a header shorter than the Ladder's own delay does not shorten it")
        void neverShortensTheLadder() {
            assertEquals(ladderNext(3600),
                    RetryAfter.nextRetryAt(ladderNext(3600), "30", 429, NOW, MAX));
        }

        @Test
        @DisplayName("a header longer than the clamp is cut to the clamp")
        void clampsTheHeader() {
            assertEquals(NOW.plus(MAX),
                    RetryAfter.nextRetryAt(ladderNext(60), String.valueOf(Duration.ofDays(7).toSeconds()),
                            429, NOW, MAX));
        }

        @Test
        @DisplayName("an absurd delta-seconds is clamped rather than overflowing the arithmetic")
        void clampsAbsurdDelta() {
            Instant result = RetryAfter.nextRetryAt(ladderNext(60), "99999999999999999999", 429, NOW, MAX);
            assertEquals(ladderNext(60), result,
                    "a value that is not a whole number of seconds is unparseable, not enormous");
        }

        @Test
        @DisplayName("a clamp of zero turns the feature off, leaving the Ladder in charge")
        void zeroClampDisables() {
            assertEquals(ladderNext(60),
                    RetryAfter.nextRetryAt(ladderNext(60), "600", 429, NOW, Duration.ZERO));
        }

        @Test
        @DisplayName("the clamp cannot pull a retry in front of the Ladder either")
        void clampNeverShortensTheLadder() {
            Instant ladder = ladderNext(Duration.ofHours(36).toSeconds());
            assertEquals(ladder,
                    RetryAfter.nextRetryAt(ladder, String.valueOf(Duration.ofDays(7).toSeconds()),
                            429, NOW, MAX));
        }

        @Test
        @DisplayName("an HTTP-date beyond the clamp is cut to the clamp")
        void clampsHttpDate() {
            Instant result = RetryAfter.nextRetryAt(ladderNext(60),
                    "Mon, 28 Sep 2026 12:00:00 GMT", 503, NOW, MAX);
            assertEquals(NOW.plus(MAX), result);
            assertTrue(result.isBefore(NOW.plus(Duration.ofDays(2))));
        }
    }
}

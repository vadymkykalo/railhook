package com.webhook.platform.common.retry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which HTTP statuses are worth another Attempt, as a Subscription or Destination may spell it.
 *
 * <p>Written against the behaviour that used to be three literals inside
 * {@code RetryPolicy.isRetryable}: 408, 429 and the whole 5xx range. That set is still the
 * default, and {@link #defaultSpecReproducesTheOldHardcodedSet()} is what says so — the
 * feature is only safe because nobody who leaves the field alone notices it exists.
 *
 * <p>Deliberately a plain {@code *Test}: pure arithmetic over a string, no container — it must
 * run in the no-Docker unit job. See {@code scripts/check-test-routing.sh}.
 */
class RetryableStatusesTest {

    @Nested
    @DisplayName("the default is exactly what was hardcoded")
    class Defaults {

        @ParameterizedTest
        @ValueSource(ints = { 408, 429, 500, 502, 503, 504, 599 })
        @DisplayName("the default spec retries every status the hardcoded set retried")
        void defaultSpecReproducesTheOldHardcodedSet(int status) {
            assertTrue(RetryableStatuses.parse(RetryableStatuses.DEFAULT_SPEC).isRetryable(status));
        }

        @ParameterizedTest
        @ValueSource(ints = { 200, 201, 301, 400, 401, 403, 404, 409, 410, 422, 600 })
        @DisplayName("the default spec retries nothing the hardcoded set did not")
        void defaultSpecRetriesNothingElse(int status) {
            assertFalse(RetryableStatuses.parse(RetryableStatuses.DEFAULT_SPEC).isRetryable(status));
        }
    }

    @Nested
    @DisplayName("the grammar")
    class Grammar {

        @ParameterizedTest
        @CsvSource({
                "'500', 500, true",
                "'500', 501, false",
                "'500-599', 500, true",
                "'500-599', 599, true",
                "'500-599', 499, false",
                "'>=500', 500, true",
                "'>=500', 499, false",
                "'>500', 500, false",
                "'>500', 501, true",
                "'<=408', 408, true",
                "'<=408', 409, false",
                "'<400', 399, true",
                "'<400', 400, false",
                "'5xx', 503, true",
                "'5xx', 499, false",
                "'4xx', 404, true",
                "'408,429,5xx', 429, true",
                "'408,429,5xx', 404, false",
        })
        @DisplayName("each term form matches what it says")
        void termForms(String spec, int status, boolean retryable) {
            assertEquals(retryable, RetryableStatuses.parse(spec).isRetryable(status));
        }

        @Test
        @DisplayName("whitespace and case around terms are ignored")
        void whitespaceIgnored() {
            RetryableStatuses statuses = RetryableStatuses.parse("  408 , 5XX , >= 429 ");
            assertTrue(statuses.isRetryable(408));
            assertTrue(statuses.isRetryable(500));
            assertTrue(statuses.isRetryable(429));
        }
    }

    @Nested
    @DisplayName("exclusions win wherever they are written")
    class Exclusions {

        @Test
        @DisplayName("an excluded status inside an included range is not retryable")
        void exclusionBeatsInclusion() {
            RetryableStatuses statuses = RetryableStatuses.parse("500-599,!501");
            assertTrue(statuses.isRetryable(500));
            assertFalse(statuses.isRetryable(501));
            assertTrue(statuses.isRetryable(502));
        }

        @Test
        @DisplayName("order does not matter — an exclusion written first still wins")
        void exclusionFirstStillWins() {
            assertFalse(RetryableStatuses.parse("!501,500-599").isRetryable(501));
        }

        @Test
        @DisplayName("a range may be excluded, not only a single status")
        void excludedRange() {
            RetryableStatuses statuses = RetryableStatuses.parse(">=500,!501-509");
            assertTrue(statuses.isRetryable(500));
            assertFalse(statuses.isRetryable(505));
            assertTrue(statuses.isRetryable(510));
        }

        @Test
        @DisplayName("a spec that excludes everything it includes retries nothing, rather than failing")
        void exclusionsMayEmptyTheSet() {
            assertFalse(RetryableStatuses.parse("500,!500").isRetryable(500));
        }
    }

    @Nested
    @DisplayName("refuses to guess")
    class Validation {

        @ParameterizedTest
        @ValueSource(strings = {
                "", "   ", "abc", "500,", ",500", "500,,502", "5xxx", "50x",
                "500-", "-500", "599-500", "500-600", "99", "600", ">=", ">=abc",
                "!", "!!500", "500..599", "2xx-3xx",
        })
        @DisplayName("a malformed spec throws instead of substituting the default")
        void malformedThrows(String spec) {
            assertThrows(IllegalArgumentException.class, () -> RetryableStatuses.parse(spec));
        }

        @Test
        @DisplayName("null throws — every row carries a spec, so null is a bug and not a default")
        void nullThrows() {
            assertThrows(IllegalArgumentException.class, () -> RetryableStatuses.parse(null));
        }

        @Test
        @DisplayName("a wall of pasted-in terms is rejected rather than stored")
        void tooManyTermsThrows() {
            String spec = "500".repeat(1).concat(",500".repeat(RetryableStatuses.MAX_TERMS));
            assertThrows(IllegalArgumentException.class, () -> RetryableStatuses.parse(spec));
        }

        @Test
        @DisplayName("validate names the field the caller actually sent, so the message is usable")
        void validateNamesTheField() {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> RetryableStatuses.validate("nonsense", "retryableStatuses"));
            assertTrue(e.getMessage().contains("retryableStatuses"), e.getMessage());
        }

        @Test
        @DisplayName("a status outside 100-599 is not an HTTP status and is refused")
        void outOfRangeThrows() {
            assertThrows(IllegalArgumentException.class, () -> RetryableStatuses.parse("100-700"));
        }
    }

    @Nested
    @DisplayName("round-trips")
    class RoundTrip {

        @Test
        @DisplayName("the declared default parses")
        void defaultParses() {
            assertEquals(RetryableStatuses.parse(RetryableStatuses.DEFAULT_SPEC),
                    RetryableStatuses.parse(RetryableStatuses.DEFAULT_SPEC));
        }

        @Test
        @DisplayName("two specs that mean the same set compare equal")
        void equalityIsBySet() {
            assertEquals(RetryableStatuses.parse("500-599"), RetryableStatuses.parse("5xx"));
        }
    }
}

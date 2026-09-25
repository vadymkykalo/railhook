package com.webhook.platform.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("PiiSanitizer")
class PiiSanitizerTest {

    private static PiiSanitizer.Rule builtin(String name) {
        return new PiiSanitizer.Rule(name, null, PiiSanitizer.MaskStyle.PARTIAL, true);
    }

    private static String maskCard(String json) {
        return PiiSanitizer.sanitize(json, List.of(builtin(PiiSanitizer.BUILTIN_CARD)));
    }

    private static final List<PiiSanitizer.Rule> ALL_BUILTINS = List.of(
            builtin(PiiSanitizer.BUILTIN_EMAIL),
            builtin(PiiSanitizer.BUILTIN_PHONE),
            builtin(PiiSanitizer.BUILTIN_CARD));

    // The payload is attacker-chosen, and backtracking key/value patterns pinned a thread for minutes.
    @Nested
    @DisplayName("hostile payloads cannot stall the sanitizer")
    class Backtracking {

        @Test
        void aLongUnterminatedKeyFullOfKeywordsIsLinear() {
            String hostile = "{\"" + "mail".repeat(40_000) + " x";

            assertTimeoutPreemptively(Duration.ofSeconds(2), () -> PiiSanitizer.sanitize(hostile, ALL_BUILTINS));
        }

        @Test
        void keysAndValuesFullOfWhatThePatternsLookForAreLinear() {
            List<String> hostile = List.of(
                    "{\"mail\":\"" + "!@".repeat(200_000),
                    "{\"mail\":\"" + "!@".repeat(200_000) + "\"}",
                    "{\"tel" + "fax".repeat(200_000),
                    "{\"pan" + "pan".repeat(200_000),
                    "{\"" + "\"mail\":".repeat(100_000));

            for (String payload : hostile) {
                assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
                    PiiSanitizer.sanitize(payload, ALL_BUILTINS);
                    PiiSanitizer.detect(payload);
                });
            }
        }

        @Test
        void aKeyAfterValuesOfOtherShapesIsStillFound() {
            String json = "{\"id\":42,\"tags\":[\"a\",\"email\"],\"ok\":true,\"contact\":{\"email\":\"john@example.com\"}}";
            String masked = PiiSanitizer.sanitize(json, List.of(builtin(PiiSanitizer.BUILTIN_EMAIL)));
            assertFalse(masked.contains("john@example.com"), masked);
            assertTrue(masked.contains("\"tags\":[\"a\",\"email\"]"), masked);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"card", "cardNumber", "pan", "creditCard", "debit_card", "accountNumber"})
    void masksACardNumberUnderACardishKey(String key) {
        String masked = maskCard("{\"" + key + "\":\"4242424242424242\"}");

        assertFalse(masked.contains("4242424242424242"), masked);
        assertTrue(masked.contains("42***42"), masked);
    }

    // Payment providers hold the PAN under "number", which is card-ish only by the object around it.
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "{\"card\":{\"number\":\"4242424242424242\"}} | 4242424242424242",
            "{\"creditCard\":{\"number\":\"4111111111111111\"}} | 4111111111111111",
            "{\"payment\":{\"pan\":\"4242424242424242\"}} | 4242424242424242",
    })
    void masksANumberInsideACardObject(String json, String pan) {
        assertFalse(maskCard(json).contains(pan));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"cardLast4\":\"4242\"}",
            "{\"order\":{\"number\":\"1234567890123456\"}}",
    })
    void leavesALastFourOrANonCardNumberAlone(String json) {
        assertEquals(json, maskCard(json));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "email | {\"email\":\"jordan@example.com\"} | jordan@example.com",
            "email | {\"customer_email_address\":\"john@example.com\"} | john@example.com",
            "phone | {\"phone\":\"+1 555 0134\"} | 555 0134",
    })
    void masksTheOtherBuiltins(String rule, String json, String secret) {
        String name = "email".equals(rule) ? PiiSanitizer.BUILTIN_EMAIL : PiiSanitizer.BUILTIN_PHONE;
        String masked = PiiSanitizer.sanitize(json, List.of(builtin(name)));
        assertFalse(masked.contains(secret), masked);
    }

    @Test
    void returnsThePayloadWhenNoRuleIsEnabled() {
        String json = "{\"card\":\"4242424242424242\"}";
        assertEquals(json, PiiSanitizer.sanitize(json,
                List.of(new PiiSanitizer.Rule(PiiSanitizer.BUILTIN_CARD, null, PiiSanitizer.MaskStyle.PARTIAL, false))));
    }

    @Test
    void survivesNullAndBlankInput() {
        assertNull(PiiSanitizer.sanitize(null, List.of(builtin(PiiSanitizer.BUILTIN_CARD))));
        assertEquals("", PiiSanitizer.sanitize("", List.of(builtin(PiiSanitizer.BUILTIN_CARD))));
    }

    @Test
    void detectReportsACardInsideACardObject() {
        List<PiiSanitizer.PiiMatch> found = PiiSanitizer.detect("{\"card\":{\"number\":\"4242424242424242\"}}");
        assertTrue(found.stream().anyMatch(m -> PiiSanitizer.BUILTIN_CARD.equals(m.patternName)),
                "detect() missed the PAN, so a preview would have called the payload clean");
    }
}

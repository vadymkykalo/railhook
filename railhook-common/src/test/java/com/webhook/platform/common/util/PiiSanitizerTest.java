package com.webhook.platform.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The masking a payload gets before anyone outside sees it.
 *
 * <p>This had no tests at all, which is how the gap in {@link #cardObjects()}
 * survived: the rule reads "card, PARTIAL, enabled" in the UI whatever it
 * actually matches, and nothing else reported the difference until the preview
 * endpoint was wired to a screen.
 */
@DisplayName("PiiSanitizer")
class PiiSanitizerTest {

    private static PiiSanitizer.Rule builtin(String name) {
        return new PiiSanitizer.Rule(name, null, PiiSanitizer.MaskStyle.PARTIAL, true);
    }

    private static String maskCard(String json) {
        return PiiSanitizer.sanitize(json, List.of(builtin(PiiSanitizer.BUILTIN_CARD)));
    }

    @Nested
    @DisplayName("hostile payloads cannot stall the sanitizer")
    class Backtracking {

        /*
         * CodeQL java/polynomial-redos: the builtin key patterns were `"([^"]*(?:mail)[^"]*?)"`,
         * which backtracks quadratically over one long quoted string full of the keyword. The
         * payload is whatever a webhook sender or the preview endpoint is handed, so a single
         * request could pin a worker thread for minutes.
         */
        @Test
        void aLongUnterminatedKeyFullOfKeywordsIsLinear() {
            String hostile = "{\"" + "mail".repeat(40_000) + " x";
            List<PiiSanitizer.Rule> rules = List.of(
                    builtin(PiiSanitizer.BUILTIN_EMAIL),
                    builtin(PiiSanitizer.BUILTIN_PHONE),
                    builtin(PiiSanitizer.BUILTIN_CARD));

            assertTimeoutPreemptively(java.time.Duration.ofSeconds(2),
                    () -> PiiSanitizer.sanitize(hostile, rules));
        }

        /*
         * The shapes CodeQL still reported once the key segments were bounded: a bounded
         * repetition on either side of the keyword is still ambiguous, and so is `[^"]+@[^"]+`
         * over a value full of '@'. The key and the value are now read once each and judged in
         * code, so none of these can backtrack.
         */
        @Test
        void keysAndValuesFullOfWhatThePatternsLookForAreLinear() {
            List<PiiSanitizer.Rule> rules = List.of(
                    builtin(PiiSanitizer.BUILTIN_EMAIL),
                    builtin(PiiSanitizer.BUILTIN_PHONE),
                    builtin(PiiSanitizer.BUILTIN_CARD));
            List<String> hostile = List.of(
                    "{\"mail\":\"" + "!@".repeat(200_000),
                    "{\"mail\":\"" + "!@".repeat(200_000) + "\"}",
                    "{\"tel" + "fax".repeat(200_000),
                    "{\"pan" + "pan".repeat(200_000),
                    "{\"" + "\"mail\":".repeat(100_000));

            for (String payload : hostile) {
                assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), () -> {
                    PiiSanitizer.sanitize(payload, rules);
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

        @Test
        void aRealisticKeyIsStillMasked() {
            String masked = PiiSanitizer.sanitize("{\"customer_email_address\":\"john@example.com\"}",
                    List.of(builtin(PiiSanitizer.BUILTIN_EMAIL)));
            assertFalse(masked.contains("john@example.com"), masked);
        }
    }

    @Nested
    @DisplayName("card numbers held directly by a card-ish key")
    class FlatCards {

        @Test
        void masksACardNumberUnderACardKey() {
            assertEquals("{\"card\": \"42***42\"}", maskCard("{\"card\":\"4242424242424242\"}"));
        }

        @Test
        void masksACardNumberUnderACompoundKey() {
            assertEquals("{\"cardNumber\": \"42***42\"}", maskCard("{\"cardNumber\":\"4242424242424242\"}"));
        }

        @Test
        void masksThePanSynonyms() {
            for (String key : new String[] { "pan", "creditCard", "debit_card", "accountNumber" }) {
                String masked = maskCard("{\"" + key + "\":\"4242424242424242\"}");
                assertTrue(masked.contains("42***42"), key + " should have been masked, got: " + masked);
            }
        }

        @Test
        void leavesShortNumbersAlone() {
            // A four-digit last-four is not a PAN and masking it destroys the
            // only part a support agent is allowed to see.
            assertEquals("{\"cardLast4\":\"4242\"}", maskCard("{\"cardLast4\":\"4242\"}"));
        }
    }

    @Nested
    @DisplayName("card numbers inside a card object")
    class CardObjects {

        @Test
        void masksANumberHeldByACardObject() {
            // The shape every payment provider uses. The key holding the digits
            // is "number", which is not card-ish on its own — the context is the
            // object it sits in.
            String masked = maskCard("{\"card\":{\"number\":\"4242424242424242\"}}");

            assertFalse(masked.contains("4242424242424242"), "the PAN was left in the clear: " + masked);
            assertTrue(masked.contains("42***42"), masked);
        }

        @Test
        void masksANumberHeldByACreditCardObject() {
            String masked = maskCard("{\"creditCard\":{\"number\":\"4111111111111111\"}}");
            assertFalse(masked.contains("4111111111111111"), masked);
        }

        @Test
        void masksAPanHeldByACardObject() {
            String masked = maskCard("{\"payment\":{\"pan\":\"4242424242424242\"}}");
            assertFalse(masked.contains("4242424242424242"), masked);
        }

        @Test
        void leavesANumberAloneWhenNothingAroundItSaysCard() {
            // An order number of card-ish length is not a card. Masking it would
            // corrupt the payload the consumer needs.
            String json = "{\"order\":{\"number\":\"1234567890123456\"}}";
            assertEquals(json, maskCard(json));
        }
    }

    @Nested
    @DisplayName("the other built-ins")
    class OtherBuiltins {

        @Test
        void masksAnEmail() {
            String masked = PiiSanitizer.sanitize(
                    "{\"email\":\"jordan@example.com\"}", List.of(builtin(PiiSanitizer.BUILTIN_EMAIL)));
            assertFalse(masked.contains("jordan@example.com"), masked);
        }

        @Test
        void masksAPhone() {
            String masked = PiiSanitizer.sanitize(
                    "{\"phone\":\"+1 555 0134\"}", List.of(builtin(PiiSanitizer.BUILTIN_PHONE)));
            assertFalse(masked.contains("555 0134"), masked);
        }
    }

    @Nested
    @DisplayName("what it refuses to touch")
    class Untouched {

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
    }

    @Nested
    @DisplayName("detect(), which reports without changing anything")
    class Detect {

        @Test
        void reportsACardInsideACardObject() {
            List<PiiSanitizer.PiiMatch> found = PiiSanitizer.detect("{\"card\":{\"number\":\"4242424242424242\"}}");
            assertTrue(found.stream().anyMatch(m -> PiiSanitizer.BUILTIN_CARD.equals(m.patternName)),
                    "detect() missed the PAN, so a preview would have called the payload clean");
        }
    }
}

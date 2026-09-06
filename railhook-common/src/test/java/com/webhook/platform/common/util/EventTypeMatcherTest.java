package com.webhook.platform.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

class EventTypeMatcherTest {

    // ── matches() ──

    @Nested
    @DisplayName("Exact match")
    class ExactMatch {
        @Test
        void exactMatch() {
            assertTrue(EventTypeMatcher.matches("order.completed", "order.completed"));
        }

        @Test
        void exactMismatch() {
            assertFalse(EventTypeMatcher.matches("order.completed", "order.shipped"));
        }

        @Test
        void singleSegment() {
            assertTrue(EventTypeMatcher.matches("ping", "ping"));
            assertFalse(EventTypeMatcher.matches("ping", "pong"));
        }
    }

    @Nested
    @DisplayName("Single-segment wildcard (*)")
    class SingleWildcard {
        @Test
        void matchesSingleSegment() {
            assertTrue(EventTypeMatcher.matches("order.*", "order.completed"));
            assertTrue(EventTypeMatcher.matches("order.*", "order.shipped"));
        }

        @Test
        void doesNotMatchMultipleSegments() {
            assertFalse(EventTypeMatcher.matches("order.*", "order.line.added"));
        }

        @Test
        void wildcardAtStart() {
            assertTrue(EventTypeMatcher.matches("*.completed", "order.completed"));
            assertFalse(EventTypeMatcher.matches("*.completed", "order.line.completed"));
        }

        @Test
        void wildcardInMiddle() {
            assertTrue(EventTypeMatcher.matches("order.*.completed", "order.line.completed"));
            assertFalse(EventTypeMatcher.matches("order.*.completed", "order.completed"));
        }

        @Test
        void standaloneStarMatchesSingleSegment() {
            assertTrue(EventTypeMatcher.matches("*", "ping"));
            assertFalse(EventTypeMatcher.matches("*", "order.completed"));
        }
    }

    @Nested
    @DisplayName("Multi-segment wildcard (**)")
    class MultiWildcard {
        @Test
        void matchesAnythingRemaining() {
            assertTrue(EventTypeMatcher.matches("order.**", "order.completed"));
            assertTrue(EventTypeMatcher.matches("order.**", "order.line.added"));
            assertTrue(EventTypeMatcher.matches("order.**", "order.a.b.c.d"));
        }

        @Test
        void catchAll() {
            assertTrue(EventTypeMatcher.matches("**", "order.completed"));
            assertTrue(EventTypeMatcher.matches("**", "ping"));
            assertTrue(EventTypeMatcher.matches("**", "a.b.c.d.e"));
        }

        @Test
        void doubleStarInMiddle() {
            assertTrue(EventTypeMatcher.matches("order.**.completed", "order.completed"));
            assertTrue(EventTypeMatcher.matches("order.**.completed", "order.line.completed"));
            assertTrue(EventTypeMatcher.matches("order.**.completed", "order.a.b.completed"));
        }

        @Test
        void doubleStarDoesNotMatchWrongSuffix() {
            assertFalse(EventTypeMatcher.matches("order.**.completed", "order.line.shipped"));
        }
    }

    @Nested
    @DisplayName("Edge cases")
    class EdgeCases {
        @Test
        void nullPattern() {
            assertFalse(EventTypeMatcher.matches(null, "order.completed"));
        }

        @Test
        void nullEventType() {
            assertFalse(EventTypeMatcher.matches("order.*", null));
        }

        @Test
        void bothNull() {
            assertFalse(EventTypeMatcher.matches(null, null));
        }

        @Test
        void emptyStrings() {
            assertFalse(EventTypeMatcher.matches("", "order.completed"));
            assertFalse(EventTypeMatcher.matches("order.*", ""));
        }
    }

    // ── isWildcard() ──

    @ParameterizedTest
    @CsvSource({
            "order.*, true",
            "order.**, true",
            "**, true",
            "*, true",
            "order.completed, false",
            "ping, false"
    })
    void isWildcard(String pattern, boolean expected) {
        assertEquals(expected, EventTypeMatcher.isWildcard(pattern));
    }

    // ── isValidPattern() ──

    @Nested
    @DisplayName("Pattern validation")
    class Validation {
        @ParameterizedTest
        @CsvSource({
                "order.completed, true",
                "order.*, true",
                "order.**, true",
                "**, true",
                "*, true",
                "order.line.*, true",
                "order.**.completed, true",
                "ping, true",
                "order_item.created, true",
        })
        void validPatterns(String pattern, boolean expected) {
            assertEquals(expected, EventTypeMatcher.isValidPattern(pattern));
        }

        @ParameterizedTest
        @CsvSource({
                "Order.completed",
                "order..completed",
                ".order",
                "order.",
                "123order",
                "order.CREATED",
        })
        void invalidPatterns(String pattern) {
            assertFalse(EventTypeMatcher.isValidPattern(pattern));
        }

        @Test
        void nullAndBlank() {
            assertFalse(EventTypeMatcher.isValidPattern(null));
            assertFalse(EventTypeMatcher.isValidPattern(""));
            assertFalse(EventTypeMatcher.isValidPattern("   "));
        }
    }
}

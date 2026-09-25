package com.webhook.platform.common.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

class EventTypeMatcherTest {

    @ParameterizedTest(name = "{0} vs {1} -> {2}")
    @CsvSource(nullValues = "NULL", value = {
            "order.completed, order.completed, true",
            "order.completed, order.shipped, false",
            "ping, ping, true",
            "ping, pong, false",
            "order.*, order.completed, true",
            "order.*, order.shipped, true",
            "order.*, order.line.added, false",
            "*.completed, order.completed, true",
            "*.completed, order.line.completed, false",
            "order.*.completed, order.line.completed, true",
            "order.*.completed, order.completed, false",
            "*, ping, true",
            "*, order.completed, false",
            "order.**, order.completed, true",
            "order.**, order.line.added, true",
            "order.**, order.a.b.c.d, true",
            "**, order.completed, true",
            "**, ping, true",
            "**, a.b.c.d.e, true",
            "order.**.completed, order.completed, true",
            "order.**.completed, order.line.completed, true",
            "order.**.completed, order.a.b.completed, true",
            "order.**.completed, order.line.shipped, false",
            "NULL, order.completed, false",
            "order.*, NULL, false",
            "NULL, NULL, false",
            "'', order.completed, false",
            "order.*, '', false",
    })
    void matches(String pattern, String eventType, boolean expected) {
        assertEquals(expected, EventTypeMatcher.matches(pattern, eventType));
    }

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
            "Order.completed, false",
            "order..completed, false",
            ".order, false",
            "order., false",
            "123order, false",
            "order.CREATED, false",
    })
    void isValidPattern(String pattern, boolean expected) {
        assertEquals(expected, EventTypeMatcher.isValidPattern(pattern));
    }

    @Test
    void nullAndBlankAreNotValidPatterns() {
        assertFalse(EventTypeMatcher.isValidPattern(null));
        assertFalse(EventTypeMatcher.isValidPattern(""));
        assertFalse(EventTypeMatcher.isValidPattern("   "));
    }
}

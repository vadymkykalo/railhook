package com.webhook.platform.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class LogSanitizerTest {

    @Test
    @DisplayName("a newline cannot forge a second log line")
    void newlineIsNeutralised() {
        String forged = LogSanitizer.forLog("GET /api\nWARN  Organization deleted");

        assertFalse(forged.contains("\n"));
        assertEquals("GET /api_WARN  Organization deleted", forged);
    }

    @Test
    @DisplayName("a carriage return counts too, on its own or paired")
    void carriageReturnIsNeutralised() {
        assertEquals("a__b", LogSanitizer.forLog("a\r\nb"));
        assertEquals("a_b", LogSanitizer.forLog("a\rb"));
    }

    @Test
    @DisplayName("the invisible controls go as well, not only the two that break lines")
    void otherControlCharactersAreNeutralised() {
        assertEquals("a_b", LogSanitizer.forLog("a\tb"));
        assertEquals("a_b", LogSanitizer.forLog("a\u0000b"));
        assertEquals("GET /api", LogSanitizer.forLog("GET /api"));
    }

    @Test
    @DisplayName("an ordinary value is passed through untouched")
    void ordinaryValueIsUnchanged() {
        String clean = "GET /api/v1/deliveries?limit=50";

        // Same instance: a request that is not an attack pays nothing.
        assertSame(clean, LogSanitizer.forLog(clean));
    }

    @Test
    @DisplayName("null stays null rather than becoming the word")
    void nullIsPreserved() {
        assertNull(LogSanitizer.forLog(null));
    }
}

package com.webhook.platform.common.util;

import java.util.regex.Pattern;

/**
 * Replaces control characters before a value is logged, so an outside value cannot end our log
 * line and forge the next one. Replaced with {@code _} rather than dropped, so a tampered value
 * stays recognisable.
 */
public final class LogSanitizer {

    /** Matches {@link Character#isISOControl}: {@code \\p{Cntrl}} misses the C1 range. */
    private static final Pattern CONTROL_CHARACTERS = Pattern.compile("[\\p{Cntrl}\\u0080-\\u009F]");

    private LogSanitizer() {
    }

    public static String forLog(String value) {
        if (value == null) {
            return null;
        }
        return CONTROL_CHARACTERS.matcher(value).replaceAll("_");
    }
}

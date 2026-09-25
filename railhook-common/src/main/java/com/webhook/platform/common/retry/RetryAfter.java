package com.webhook.platform.common.retry;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Bounds what a receiver's {@code Retry-After} may do to the Retry Ladder. Honoured only on 429
 * and 503; elsewhere it is often a header left on by a proxy. It may only push a retry later,
 * never sooner than the Ladder. It is clamped, so one bad response cannot park an obligation
 * for a year. A receiver that keeps asking for the maximum reaches the escalation hard cap
 * before its Ladder is exhausted, which is intended.
 */
public final class RetryAfter {

    /**
     * 6 hours: the largest tier both default ladders share, and 7 outgoing attempts at this clamp
     * still fit the 96h hard cap. A longer value (Hookdeck allows 7 days) would be cut short by
     * escalation rather than honoured.
     */
    public static final long DEFAULT_MAX_SECONDS = 21600;

    /** RFC 9110 date formats. The two obsolete ones still show up. */
    private static final DateTimeFormatter[] HTTP_DATES = {
            DateTimeFormatter.RFC_1123_DATE_TIME,
            DateTimeFormatter.ofPattern("EEEE, dd-MMM-yy HH:mm:ss zzz", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("EEE MMM ppd HH:mm:ss yyyy", Locale.ENGLISH)
                    .withZone(ZoneOffset.UTC),
    };

    private RetryAfter() {
    }

    /**
     * @param ladderNext returned whenever the header is absent, unparseable, inapplicable or earlier
     * @param max        the clamp; null or non-positive disables the header for this call
     */
    public static Instant nextRetryAt(Instant ladderNext, String header, int statusCode,
            Instant now, Duration max) {
        if (max == null || max.isZero() || max.isNegative()) {
            return ladderNext;
        }
        if (statusCode != 429 && statusCode != 503) {
            return ladderNext;
        }

        Instant asked = parse(header, now);
        if (asked == null || !asked.isAfter(now)) {
            return ladderNext;
        }

        Instant clamped = asked.isAfter(now.plus(max)) ? now.plus(max) : asked;
        return clamped.isAfter(ladderNext) ? clamped : ladderNext;
    }

    /** Anything unparseable is ignored, not guessed at. */
    private static Instant parse(String header, Instant now) {
        if (header == null) {
            return null;
        }
        String value = header.trim();
        if (value.isEmpty()) {
            return null;
        }

        if (isDigits(value)) {
            try {
                return now.plusSeconds(Long.parseLong(value));
            } catch (NumberFormatException | ArithmeticException e) {
                return null;
            }
        }

        for (DateTimeFormatter format : HTTP_DATES) {
            try {
                return Instant.from(format.parse(value));
            } catch (DateTimeException e) {
                // try the next format
            }
        }
        return null;
    }

    private static boolean isDigits(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }
}

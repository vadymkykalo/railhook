package com.webhook.platform.common.retry;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * What a receiver's {@code Retry-After} is allowed to do to the Retry Ladder.
 *
 * <p>The header is the only way a receiver can say "come back at this time" rather than have us
 * guess, and ignoring it is how a 429 turns into a retry storm. It is also entirely under the
 * receiver's control, so three rules bound it:
 *
 * <ol>
 *   <li><strong>Only where it means what we think it means.</strong> 429 and 503 are the two
 *       statuses RFC 9110 defines it for in the sense we want — "I am over capacity, wait". On a
 *       500 it is at best a guess and at worst a header left on by a proxy.</li>
 *   <li><strong>It may only push a retry later.</strong> A receiver asking to be called back
 *       sooner than the Ladder says is asking us to retry harder than its owner configured, and
 *       that is not the receiver's decision to make.</li>
 *   <li><strong>It is clamped.</strong> One line of a misconfigured response otherwise parks an
 *       obligation for a year. Beyond the clamp the obligation keeps the clamp, not the
 *       Ladder — the receiver has still told us something true.</li>
 * </ol>
 *
 * <p>Note that a clamp long enough to matter interacts with the escalation hard cap: an
 * obligation still pending past that cap is escalated to the DLQ whatever its Ladder says. A
 * receiver that keeps asking for the maximum will therefore reach Failed Messages before its
 * Ladder is exhausted, which is the right outcome for a receiver that has spent days asking to
 * be left alone.
 */
public final class RetryAfter {

    /**
     * 6 hours. The largest tier both {@link RetryLadderDefaults} ladders share, so honouring a
     * {@code Retry-After} never parks an obligation longer than the Ladder itself already can,
     * and 7 outgoing attempts at the clamp still fit inside the 96h delivery hard cap.
     *
     * <p>Hookdeck allows 7 days. We do not, because our hard cap is 96 hours: a week-long
     * {@code Retry-After} would be silently cut short by escalation rather than honoured, which
     * is worse than saying up front that we do not honour it.
     */
    public static final long DEFAULT_MAX_SECONDS = 21600;

    /**
     * RFC 9110 names three date formats. {@code RFC_1123_DATE_TIME} is the one anything written
     * this century sends; the other two are here because "obsolete" is not "absent" and a
     * receiver that sends one is still telling us when to come back.
     */
    private static final DateTimeFormatter[] HTTP_DATES = {
            DateTimeFormatter.RFC_1123_DATE_TIME,
            DateTimeFormatter.ofPattern("EEEE, dd-MMM-yy HH:mm:ss zzz", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("EEE MMM ppd HH:mm:ss yyyy", Locale.ENGLISH)
                    .withZone(ZoneOffset.UTC),
    };

    private RetryAfter() {
    }

    /**
     * When the next Attempt is due, given what the Ladder decided and what the receiver asked
     * for.
     *
     * @param ladderNext when the Retry Ladder says the next Attempt is due — the answer whenever
     *                   the header is absent, unparseable, inapplicable or shorter than this
     * @param header     the raw {@code Retry-After} value, or null
     * @param statusCode the status it arrived with; only 429 and 503 are honoured
     * @param now        the instant the response was classified, which delta-seconds counts from
     * @param max        the clamp; null or non-positive turns the feature off for this call
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

    /**
     * The instant the header names, or null when there is not one to be had. Both wire formats;
     * anything else is ignored rather than guessed at, because guessing here means either
     * retrying a rate-limited receiver immediately or parking an obligation on a typo.
     */
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
                // Too many digits to be a number of seconds anybody meant.
                return null;
            }
        }

        for (DateTimeFormatter format : HTTP_DATES) {
            try {
                return Instant.from(format.parse(value));
            } catch (DateTimeException e) {
                // Try the next one; the last failure is not worth logging from a pure function.
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

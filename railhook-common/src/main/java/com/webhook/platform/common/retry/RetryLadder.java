package com.webhook.platform.common.retry;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The schedule of how long to wait before each successive attempt, and how many attempts
 * there are before the obligation is abandoned. Applies to both directions: an outgoing
 * Delivery reads it off its own row, an incoming Forward off its Destination.
 *
 * <p>No defaults live here, on purpose. Every row carries its own ladder via a column default,
 * so there is nothing to substitute when one looks malformed — and substituting was the old
 * behaviour, which handed a customer who mistyped {@code retry_delays} a policy that was neither
 * theirs nor documented. {@link #parse} throws, and {@link #validate(String, String)} lets the API
 * reject the mistake where it is made. The per-direction defaults live in {@link RetryLadderDefaults}.
 */
public final class RetryLadder {

    /** Longer than this is likelier a typo than an intent, and keeps the arithmetic clear of overflow. */
    public static final long MAX_TIER_SECONDS = 30L * 24 * 60 * 60;

    /** Upper bound on tiers, so a pasted-in wall of numbers is rejected rather than stored. */
    public static final int MAX_TIERS = 32;

    /** Upper bound on attempts, for the same reason. */
    public static final int MAX_ATTEMPTS_LIMIT = 100;

    private final List<Long> delaysSeconds;
    private final int maxAttempts;

    private RetryLadder(List<Long> delaysSeconds, int maxAttempts) {
        this.delaysSeconds = delaysSeconds;
        this.maxAttempts = maxAttempts;
    }

    /** @throws IllegalArgumentException with a message meant to be shown to whoever supplied the value */
    public static RetryLadder parse(String delaysCsv, int maxAttempts) {
        List<Long> delays = parseDelays(delaysCsv, "retryDelays");
        requireAttemptsInRange(maxAttempts, "maxAttempts");
        return new RetryLadder(delays, maxAttempts);
    }

    /**
     * Validates without building one, at the point a caller supplies it. {@code delaysField} is
     * quoted in the error, so the message names whatever the caller actually sent.
     */
    public static void validate(String delaysCsv, String delaysField) {
        parseDelays(delaysCsv, delaysField);
    }

    /** @see #validate(String, String) */
    public static void validate(String delaysCsv, String delaysField,
            Integer maxAttempts, String maxAttemptsField) {
        parseDelays(delaysCsv, delaysField);
        if (maxAttempts != null) {
            requireAttemptsInRange(maxAttempts, maxAttemptsField);
        }
    }

    private static List<Long> parseDelays(String delaysCsv, String field) {
        if (delaysCsv == null || delaysCsv.isBlank()) {
            throw new IllegalArgumentException(
                    field + " must not be empty — supply at least one delay in seconds, "
                            + "for example \"" + RetryLadderDefaults.OUTGOING_DELAYS + "\"");
        }

        String[] parts = delaysCsv.split(",", -1);
        if (parts.length > MAX_TIERS) {
            throw new IllegalArgumentException(
                    field + " has " + parts.length + " tiers, which exceeds the maximum of " + MAX_TIERS);
        }

        List<Long> delays = new ArrayList<>(parts.length);
        for (int i = 0; i < parts.length; i++) {
            String raw = parts[i].trim();
            long value;
            try {
                value = Long.parseLong(raw);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        field + " tier " + (i + 1) + " is \"" + raw + "\", which is not a whole number of seconds");
            }
            if (value <= 0) {
                throw new IllegalArgumentException(
                        field + " tier " + (i + 1) + " is " + value + "; every delay must be greater than zero");
            }
            if (value > MAX_TIER_SECONDS) {
                throw new IllegalArgumentException(
                        field + " tier " + (i + 1) + " is " + value + " seconds, which exceeds the maximum of "
                                + MAX_TIER_SECONDS + " (30 days)");
            }
            delays.add(value);
        }
        return Collections.unmodifiableList(delays);
    }

    private static void requireAttemptsInRange(int maxAttempts, String field) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException(field + " is " + maxAttempts + "; at least one attempt is required");
        }
        if (maxAttempts > MAX_ATTEMPTS_LIMIT) {
            throw new IllegalArgumentException(
                    field + " is " + maxAttempts + ", which exceeds the maximum of " + MAX_ATTEMPTS_LIMIT);
        }
    }

    public List<Long> delaysSeconds() {
        return delaysSeconds;
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    /**
     * The tier that applies to the given 1-indexed attempt, before jitter. Attempts past the
     * end of the ladder clamp to its last tier.
     *
     * <p>Separate from {@link #nextRetryAt} so the tier arithmetic can be asserted exactly,
     * rather than through the jitter range.
     */
    public long baseDelaySeconds(int attemptNumber) {
        if (attemptNumber < 1) {
            throw new IllegalArgumentException("attemptNumber is " + attemptNumber + "; attempts are 1-indexed");
        }
        int index = Math.min(attemptNumber - 1, delaysSeconds.size() - 1);
        return delaysSeconds.get(index);
    }

    /**
     * When the attempt after {@code attemptNumber} becomes due: the applicable tier with
     * full jitter (50%–150% of it) so a burst of same-tier retries does not stampede.
     */
    public Instant nextRetryAt(int attemptNumber) {
        long base = baseDelaySeconds(attemptNumber);
        double jitterMultiplier = 0.5 + ThreadLocalRandom.current().nextDouble(1.0);
        return Instant.now().plusSeconds((long) (base * jitterMultiplier));
    }

    /** True once {@code attemptNumber} attempts have been made and no more are allowed. */
    public boolean isExhausted(int attemptNumber) {
        return attemptNumber >= maxAttempts;
    }

    /**
     * Upper bound on the total time an obligation can spend retrying before the ladder is
     * exhausted: every tier hit at the top of {@link #nextRetryAt}'s jitter range, summed
     * across {@link #maxAttempts} attempts with the same last-tier clamp.
     *
     * <p>Used to check the ladder actually fits inside the age cap past which a delivery is
     * escalated to DLQ regardless of attempt count — if it does not, the last tiers can
     * never fire. See {@link #requireFitsWithin}.
     */
    public long worstCaseSpanSeconds() {
        long total = 0;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            total += Math.round(baseDelaySeconds(attempt) * 1.5);
        }
        return total;
    }

    /**
     * Fails fast when this ladder's worst case does not fit inside a hard cap, naming both
     * so the operator can see which of the two to move.
     */
    public void requireFitsWithin(long hardCapSeconds, String ladderName, String capName) {
        long worstCase = worstCaseSpanSeconds();
        if (worstCase > hardCapSeconds) {
            throw new IllegalStateException(String.format(
                    "Retry ladder/escalation cap mismatch: the %s ladder %s over %d attempts has a worst-case "
                            + "span of %ds (%.1fh), which exceeds %s of %ds (%.1fh). At that cap the later retry "
                            + "tiers would never fire before the obligation is escalated to DLQ. Either shorten "
                            + "the ladder or raise the cap, so the two agree.",
                    ladderName, delaysSeconds, maxAttempts,
                    worstCase, worstCase / 3600.0,
                    capName, hardCapSeconds, hardCapSeconds / 3600.0));
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof RetryLadder other)) {
            return false;
        }
        return maxAttempts == other.maxAttempts && delaysSeconds.equals(other.delaysSeconds);
    }

    @Override
    public int hashCode() {
        return 31 * delaysSeconds.hashCode() + maxAttempts;
    }

    @Override
    public String toString() {
        return "RetryLadder" + delaysSeconds + " x" + maxAttempts;
    }
}

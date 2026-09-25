package com.webhook.platform.common.retry;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Delays between attempts and how many attempts there are, for both directions. No defaults
 * here: every row has its own ladder via a column default. Substituting a default for a
 * malformed ladder used to hand customers an undocumented policy, so {@link #parse} throws.
 * Per-direction defaults are in {@link RetryLadderDefaults}.
 */
public final class RetryLadder {

    /** Longer is likelier a typo, and this keeps the arithmetic clear of overflow. */
    public static final long MAX_TIER_SECONDS = 30L * 24 * 60 * 60;

    public static final int MAX_TIERS = 32;

    public static final int MAX_ATTEMPTS_LIMIT = 100;

    private final List<Long> delaysSeconds;
    private final int maxAttempts;

    private RetryLadder(List<Long> delaysSeconds, int maxAttempts) {
        this.delaysSeconds = delaysSeconds;
        this.maxAttempts = maxAttempts;
    }

    public static RetryLadder parse(String delaysCsv, int maxAttempts) {
        List<Long> delays = parseDelays(delaysCsv, "retryDelays");
        requireAttemptsInRange(maxAttempts, "maxAttempts");
        return new RetryLadder(delays, maxAttempts);
    }

    public static void validate(String delaysCsv, String delaysField) {
        parseDelays(delaysCsv, delaysField);
    }

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

    /** Attempts past the end of the ladder clamp to its last tier. */
    public long baseDelaySeconds(int attemptNumber) {
        if (attemptNumber < 1) {
            throw new IllegalArgumentException("attemptNumber is " + attemptNumber + "; attempts are 1-indexed");
        }
        int index = Math.min(attemptNumber - 1, delaysSeconds.size() - 1);
        return delaysSeconds.get(index);
    }

    /** 50% to 150% jitter so a burst of same-tier retries does not stampede. */
    public Instant nextRetryAt(int attemptNumber) {
        long base = baseDelaySeconds(attemptNumber);
        double jitterMultiplier = 0.5 + ThreadLocalRandom.current().nextDouble(1.0);
        return Instant.now().plusSeconds((long) (base * jitterMultiplier));
    }

    public boolean isExhausted(int attemptNumber) {
        return attemptNumber >= maxAttempts;
    }

    /**
     * Every tier at the top of the jitter range. If this exceeds the age cap past which a delivery
     * is escalated regardless of attempts, the last tiers can never fire.
     */
    public long worstCaseSpanSeconds() {
        long total = 0;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            total += Math.round(baseDelaySeconds(attempt) * 1.5);
        }
        return total;
    }

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

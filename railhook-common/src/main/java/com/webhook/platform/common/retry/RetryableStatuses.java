package com.webhook.platform.common.retry;

import java.util.BitSet;
import java.util.Objects;

/**
 * Which HTTP statuses are worth another Attempt. A Subscription or a Destination may say;
 * {@link #DEFAULT_SPEC} is what they get when they do not.
 *
 * <p>Separate from {@link RetryLadder}, which says <em>when</em> the next Attempt is due. This
 * says <em>whether</em> one is owed at all, and the two answer to different people: a customer
 * tunes the ladder to how long their receiver may be down, and tunes this to what their
 * receiver's statuses actually mean. A gateway that answers 500 while it reloads is worth
 * retrying; the same 500 from an application that has rejected the payload is not.
 *
 * <p>No fallback lives here, for the reason {@link RetryLadder} spells out: every row carries a
 * spec via a column default, so a spec that does not parse is a bug to surface rather than a
 * value to substitute. {@link #parse} throws and {@link #validate} lets the api reject the
 * mistake where it was made.
 *
 * <h2>The grammar</h2>
 *
 * A comma-separated list of terms. Each term is one of
 *
 * <ul>
 *   <li>{@code 429} — exactly that status</li>
 *   <li>{@code 500-599} — an inclusive range</li>
 *   <li>{@code 5xx} — shorthand for {@code 500-599}</li>
 *   <li>{@code >=500}, {@code >500}, {@code <=408}, {@code <400} — open at one end, bounded by
 *       the range of real HTTP statuses at the other</li>
 * </ul>
 *
 * and any term may be prefixed with {@code !} to exclude it. <strong>Exclusions win wherever
 * they are written</strong>, so {@code 5xx,!501} and {@code !501,5xx} mean the same thing —
 * order-dependent rules read as if they were a firewall, and this is not one.
 */
public final class RetryableStatuses {

    /**
     * 408, 429 and every 5xx: the set {@code RetryPolicy.isRetryable} spelled out in literals
     * before it was configurable. It stays the default so that leaving the field alone changes
     * nothing, and {@code SchemaRetryLadderDefaultsTest} pins it against the Flyway defaults.
     */
    public static final String DEFAULT_SPEC = "408,429,500-599";

    /** Real HTTP statuses start here; anything below is not a status line. */
    public static final int MIN_STATUS = 100;

    /** And end here. 6xx is not a thing, however many proxies invent one. */
    public static final int MAX_STATUS = 599;

    /** Upper bound on terms, so a pasted-in wall is rejected rather than stored. */
    public static final int MAX_TERMS = 32;

    private final BitSet retryable;

    private RetryableStatuses(BitSet retryable) {
        this.retryable = retryable;
    }

    /** @throws IllegalArgumentException with a message meant to be shown to whoever supplied the value */
    public static RetryableStatuses parse(String spec) {
        return new RetryableStatuses(compile(spec, "retryableStatuses"));
    }

    /**
     * Validates without building one, at the point a caller supplies it. {@code field} is quoted
     * in the error, so the message names whatever the caller actually sent.
     */
    public static void validate(String spec, String field) {
        compile(spec, field);
    }

    /** Whether another Attempt is owed for this status. */
    public boolean isRetryable(int statusCode) {
        return statusCode >= MIN_STATUS && statusCode <= MAX_STATUS && retryable.get(statusCode);
    }

    private static BitSet compile(String spec, String field) {
        if (spec == null || spec.isBlank()) {
            throw new IllegalArgumentException(
                    field + " must not be empty — list at least one status, for example \""
                            + DEFAULT_SPEC + "\"");
        }

        String[] terms = spec.split(",", -1);
        if (terms.length > MAX_TERMS) {
            throw new IllegalArgumentException(
                    field + " has " + terms.length + " terms, which exceeds the maximum of " + MAX_TERMS);
        }

        BitSet included = new BitSet(MAX_STATUS + 1);
        BitSet excluded = new BitSet(MAX_STATUS + 1);

        for (int i = 0; i < terms.length; i++) {
            String term = terms[i].trim().toUpperCase(java.util.Locale.ROOT);
            boolean exclude = term.startsWith("!");
            if (exclude) {
                term = term.substring(1).trim();
            }
            Range range = parseTerm(term, field, i + 1);
            (exclude ? excluded : included).set(range.from(), range.to() + 1);
        }

        included.andNot(excluded);
        return included;
    }

    private static Range parseTerm(String term, String field, int position) {
        if (term.isEmpty()) {
            throw new IllegalArgumentException(
                    field + " term " + position + " is empty; remove the stray comma");
        }

        if (term.startsWith(">=") || term.startsWith("<=")) {
            int bound = wholeStatus(term.substring(2).trim(), term, field, position);
            return term.charAt(0) == '>'
                    ? new Range(bound, MAX_STATUS)
                    : new Range(MIN_STATUS, bound);
        }
        if (term.startsWith(">") || term.startsWith("<")) {
            int bound = wholeStatus(term.substring(1).trim(), term, field, position);
            return term.charAt(0) == '>'
                    ? boundedRange(bound + 1, MAX_STATUS, term, field, position)
                    : boundedRange(MIN_STATUS, bound - 1, term, field, position);
        }

        // "5xx" before the range split, or "2xx-3xx" would be read as a range of nonsense.
        if (term.length() == 3 && term.charAt(1) == 'X' && term.charAt(2) == 'X') {
            int hundreds = term.charAt(0) - '0';
            if (hundreds < 1 || hundreds > 5) {
                throw new IllegalArgumentException(
                        field + " term " + position + " is \"" + term + "\"; only 1xx through 5xx exist");
            }
            return new Range(hundreds * 100, hundreds * 100 + 99);
        }

        int dash = term.indexOf('-');
        if (dash >= 0) {
            int from = wholeStatus(term.substring(0, dash).trim(), term, field, position);
            int to = wholeStatus(term.substring(dash + 1).trim(), term, field, position);
            if (from > to) {
                throw new IllegalArgumentException(
                        field + " term " + position + " is \"" + term + "\"; a range must run upwards");
            }
            return new Range(from, to);
        }

        int exact = wholeStatus(term, term, field, position);
        return new Range(exact, exact);
    }

    /**
     * A range whose ends the operator pushed outside the status space — {@code <100} or
     * {@code >599} — selects nothing. It is legal to write and matches nothing, rather than
     * throwing: the same spec is reachable by excluding everything you included.
     */
    private static Range boundedRange(int from, int to, String term, String field, int position) {
        if (from > to) {
            return new Range(MIN_STATUS, MIN_STATUS - 1);
        }
        return new Range(Math.max(from, MIN_STATUS), Math.min(to, MAX_STATUS));
    }

    private static int wholeStatus(String raw, String term, String field, int position) {
        if (raw.isEmpty() || raw.length() > 3) {
            throw new IllegalArgumentException(
                    field + " term " + position + " is \"" + term + "\", which is not an HTTP status");
        }
        for (int i = 0; i < raw.length(); i++) {
            if (raw.charAt(i) < '0' || raw.charAt(i) > '9') {
                throw new IllegalArgumentException(
                        field + " term " + position + " is \"" + term + "\", which is not an HTTP status");
            }
        }
        int value = Integer.parseInt(raw);
        if (value < MIN_STATUS || value > MAX_STATUS) {
            throw new IllegalArgumentException(
                    field + " term " + position + " is \"" + term + "\"; HTTP statuses run from "
                            + MIN_STATUS + " to " + MAX_STATUS);
        }
        return value;
    }

    /** Inclusive at both ends; {@code to < from} means "selects nothing". */
    private record Range(int from, int to) {
        Range {
            if (to < from) {
                // Normalised so BitSet.set(from, to + 1) is a no-op rather than a throw.
                to = from - 1;
            }
        }
    }

    /** Two specs that select the same statuses are the same policy, however they were written. */
    @Override
    public boolean equals(Object o) {
        return o instanceof RetryableStatuses other && retryable.equals(other.retryable);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(retryable);
    }

    @Override
    public String toString() {
        return "RetryableStatuses" + retryable;
    }
}

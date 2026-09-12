package com.webhook.platform.common.util;

/**
 * Neutralises control characters in a value before it is written to the log.
 *
 * <p>A log entry is one line, and a reader — human or shipper — splits on that. A value somebody
 * outside chose carries whatever they typed, so a newline inside it ends our entry early and
 * starts one they wrote: a rate-limit warning turns into a forged record of an operator deleting
 * an organization, and nothing downstream can tell the two apart. The other invisible characters
 * do not split the line but let a value impersonate the separators around it.
 *
 * <p>Each one becomes {@code _} rather than being dropped, so the value stays readable and the
 * same length — a path that was tampered with should still be recognisable in the entry that
 * refused it.
 */
public final class LogSanitizer {

    /**
     * Exactly what {@link Character#isISOControl} covers: the C0 range and DEL, which
     * {@code \p{Cntrl}} gives, plus the C1 range above it, which it does not.
     */
    private static final String CONTROL_CHARACTERS = "[\\p{Cntrl}\\u0080-\\u009F]";

    private LogSanitizer() {
    }

    /**
     * Returns the value with every ISO control character replaced by {@code _}.
     *
     * <p>{@code null} stays {@code null}: the caller's format string already renders it, and
     * turning it into the word here would hide the difference between an absent value and the
     * literal text.
     *
     * <p>The regex is handed to {@link String#replaceAll} rather than a hoisted {@code Pattern},
     * and that is not an oversight. Static analysis treats {@code String}'s own replace methods
     * as the point where tainted text stops being tainted, and does not follow the same
     * substitution through {@code Matcher} — hoist the pattern for the compile it saves and the
     * log-injection finding comes straight back on every caller. The callers are a refused
     * request and an operator suspending a tenant, so there is no compile worth saving here.
     */
    public static String forLog(String value) {
        if (value == null) {
            return null;
        }
        // No match means the same instance back, so the ordinary value — which is every value
        // that is not an attack — allocates nothing.
        return value.replaceAll(CONTROL_CHARACTERS, "_");
    }
}

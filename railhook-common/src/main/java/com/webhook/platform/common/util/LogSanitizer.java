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

    private static final char REPLACEMENT = '_';

    private LogSanitizer() {
    }

    /**
     * Returns the value with every ISO control character replaced by {@code _}.
     *
     * <p>{@code null} stays {@code null}: the caller's format string already renders it, and
     * turning it into the word here would hide the difference between an absent value and the
     * literal text.
     *
     * <p>A value with nothing to neutralise is returned as-is, so the ordinary request — which is
     * every request that is not an attack — allocates nothing.
     */
    public static String forLog(String value) {
        if (value == null) {
            return null;
        }
        int first = indexOfControl(value);
        if (first < 0) {
            return value;
        }
        char[] out = value.toCharArray();
        for (int i = first; i < out.length; i++) {
            if (Character.isISOControl(out[i])) {
                out[i] = REPLACEMENT;
            }
        }
        return new String(out);
    }

    private static int indexOfControl(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) {
                return i;
            }
        }
        return -1;
    }
}

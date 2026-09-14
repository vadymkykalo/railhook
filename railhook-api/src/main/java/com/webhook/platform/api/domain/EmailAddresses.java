package com.webhook.platform.api.domain;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The one spelling of an email address the system stores and compares.
 *
 * <p>Addresses are case-insensitive in practice, and {@code users.email} is unique on its lower
 * case. Normalizing at every entry point, and again when a user row is written, is what keeps a
 * lookup by address from finding two accounts or none.
 */
public final class EmailAddresses {

    private static final String FORBIDDEN = "@,;<>\"()";

    private EmailAddresses() {
    }

    /** Trimmed and lower-cased; null stays null. */
    public static String normalize(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * One address, shaped like one: a local part, a single {@code @}, and a domain with a dot that
     * is neither its first nor its last character. Deliverability is a separate question.
     *
     * <p>Checked by scanning rather than a regular expression: the input is user-supplied, and the
     * obvious pattern backtracks polynomially on a domain made of many dots.
     */
    public static boolean isPlausible(String email) {
        if (email == null || email.length() > 254) {
            return false;
        }
        int at = email.indexOf('@');
        if (at <= 0 || at != email.lastIndexOf('@')) {
            return false;
        }
        for (int i = 0; i < email.length(); i++) {
            char c = email.charAt(i);
            if (i != at && (Character.isWhitespace(c) || FORBIDDEN.indexOf(c) >= 0)) {
                return false;
            }
        }
        String domain = email.substring(at + 1);
        return domain.length() >= 3 && domain.substring(1, domain.length() - 1).indexOf('.') >= 0;
    }

    /** A comma-separated list split into its entries, each normalized; empty entries are kept as "". */
    public static List<String> splitList(String commaSeparated) {
        return Arrays.stream(commaSeparated.split(",", -1)).map(EmailAddresses::normalize).toList();
    }
}

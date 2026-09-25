package com.webhook.platform.api.domain;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * {@code users.email} is unique on its lower case, so every entry point normalizes, or a lookup
 * by address can find two accounts or none.
 */
public final class EmailAddresses {

    private static final String FORBIDDEN = "@,;<>\"()";

    private EmailAddresses() {
    }

    public static String normalize(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Scans instead of using a regex: the obvious pattern backtracks polynomially on a
     * user-supplied domain made of many dots.
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

    /** Empty entries are kept as "". */
    public static List<String> splitList(String commaSeparated) {
        return Arrays.stream(commaSeparated.split(",", -1)).map(EmailAddresses::normalize).toList();
    }
}

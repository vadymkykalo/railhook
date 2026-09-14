package com.webhook.platform.api.domain;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The one spelling of an email address the system stores and compares.
 *
 * <p>Addresses are case-insensitive in practice, and {@code users.email} is unique on its lower
 * case. Normalizing at every entry point, and again when a user row is written, is what keeps a
 * lookup by address from finding two accounts or none.
 */
public final class EmailAddresses {

    private static final Pattern PLAUSIBLE = Pattern.compile("^[^\\s@,;<>\"()]+@[^\\s@,;<>\"()]+\\.[^\\s@,;<>\"()]+$");

    private EmailAddresses() {
    }

    /** Trimmed and lower-cased; null stays null. */
    public static String normalize(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    /** One address, shaped like one. Deliverability is a separate question. */
    public static boolean isPlausible(String email) {
        return email != null && email.length() <= 254 && PLAUSIBLE.matcher(email).matches();
    }

    /** A comma-separated list split into its entries, each normalized; empty entries are kept as "". */
    public static List<String> splitList(String commaSeparated) {
        return Arrays.stream(commaSeparated.split(",", -1)).map(EmailAddresses::normalize).toList();
    }
}

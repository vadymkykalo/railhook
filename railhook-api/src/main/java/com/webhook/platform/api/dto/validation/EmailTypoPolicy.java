package com.webhook.platform.api.dto.validation;

import java.util.Map;
import java.util.Optional;

/**
 * Only TLDs no registry has delegated are listed, so nothing here can refuse a working address.
 * A typo like {@code gmial.com} is registrable and is left to the dashboard to suggest. The UI
 * keeps the same list, and a test there fails when the two drift.
 */
public final class EmailTypoPolicy {

    private static final Map<String, String> IMPOSSIBLE_TLDS = Map.ofEntries(
            Map.entry("con", "com"),
            Map.entry("cmo", "com"),
            Map.entry("comm", "com"),
            Map.entry("ocm", "com"),
            Map.entry("cpm", "com"),
            Map.entry("vom", "com"),
            Map.entry("xom", "com"),
            Map.entry("coom", "com"),
            Map.entry("comn", "com"),
            Map.entry("nte", "net"),
            Map.entry("nett", "net"),
            Map.entry("nt", "net"),
            Map.entry("ogr", "org"),
            Map.entry("orgg", "org"));

    private EmailTypoPolicy() {
    }

    public static Optional<String> impossibleTldCorrection(String email) {
        if (email == null) {
            return Optional.empty();
        }
        String trimmed = email.trim();
        int at = trimmed.lastIndexOf('@');
        int dot = trimmed.lastIndexOf('.');
        if (at <= 0 || dot < at) {
            return Optional.empty();
        }
        String tld = trimmed.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
        String meant = IMPOSSIBLE_TLDS.get(tld);
        if (meant == null) {
            return Optional.empty();
        }
        String domain = trimmed.substring(at + 1, dot + 1).toLowerCase(java.util.Locale.ROOT);
        return Optional.of(trimmed.substring(0, at) + "@" + domain + meant);
    }
}

package com.webhook.platform.api.service.signin;

/**
 * Where the dashboard goes after a sign-in that left the site and came back.
 *
 * <p>The value makes a round trip through Google, so it is whatever the link that started the
 * sign-in said. Only a path on this origin survives: {@code //host} and {@code /\host} are both
 * read by browsers as another host, and a control character is how a value becomes a header.
 */
public final class SafeReturnPath {

    public static final String DEFAULT = "/admin/dashboard";

    static final int MAX_LENGTH = 512;

    private SafeReturnPath() {
    }

    public static String of(String candidate) {
        if (candidate == null || candidate.isEmpty() || candidate.length() > MAX_LENGTH) {
            return DEFAULT;
        }
        if (!candidate.startsWith("/") || candidate.startsWith("//")) {
            return DEFAULT;
        }
        for (int i = 0; i < candidate.length(); i++) {
            char c = candidate.charAt(i);
            if (c < 0x20 || c == 0x7f || c == '\\') {
                return DEFAULT;
            }
        }
        return candidate;
    }
}

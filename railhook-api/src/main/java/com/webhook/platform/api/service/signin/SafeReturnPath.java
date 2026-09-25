package com.webhook.platform.api.service.signin;

/**
 * The value round-trips through Google, so only a same-origin path survives: browsers read //host
 * and /\host as another host, and a control character can become a header.
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

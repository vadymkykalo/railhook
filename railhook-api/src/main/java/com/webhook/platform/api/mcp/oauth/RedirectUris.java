package com.webhook.platform.api.mcp.oauth;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Which redirect URIs an app may register, and whether a request's redirect URI is one it did.
 *
 * <p>The redirect URI is where the authorization code goes, so it is the one thing an attacker
 * needs to control to steal a grant. Two kinds are accepted: {@code https}, and plain
 * {@code http} to the loopback interface for an app running on the person's own machine (RFC 8252
 * §7.3). Everything else is refused — plain http to a network host sends the code across the
 * network in the clear, and custom schemes ({@code cursor://}, {@code vscode://}) can be claimed
 * by any app installed on the same machine. The apps that use those take an API key instead.
 */
public final class RedirectUris {

    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]", "::1");
    private static final int MAX_LENGTH = 2000;

    private RedirectUris() {
    }

    /** Why {@code uri} cannot be registered, or null if it can. */
    public static String problemWith(String uri) {
        if (uri == null || uri.isBlank()) {
            return "a redirect URI is empty";
        }
        if (uri.length() > MAX_LENGTH || uri.contains("\n")) {
            return "a redirect URI is too long";
        }
        URI parsed;
        try {
            parsed = new URI(uri);
        } catch (URISyntaxException e) {
            return "'" + uri + "' is not a URI";
        }
        if (!parsed.isAbsolute() || parsed.getHost() == null) {
            return "'" + uri + "' is not an absolute URI with a host";
        }
        if (parsed.getRawFragment() != null) {
            return "'" + uri + "' has a fragment, which a redirect URI must not have";
        }
        if (parsed.getRawUserInfo() != null) {
            return "'" + uri + "' carries credentials";
        }
        String scheme = parsed.getScheme().toLowerCase(Locale.ROOT);
        if (scheme.equals("https")) {
            return null;
        }
        if (scheme.equals("http") && isLoopback(parsed)) {
            return null;
        }
        return "'" + uri + "' must use https, or http to localhost / 127.0.0.1 / [::1]";
    }

    /**
     * Whether {@code requested} is one of {@code registered}. Exact, character for character —
     * except that a loopback URI may name any port, because a native app picks a free one each
     * time it listens (RFC 8252 §7.3).
     */
    public static boolean isRegistered(String requested, List<String> registered) {
        if (requested == null) {
            return false;
        }
        for (String candidate : registered) {
            if (candidate.equals(requested) || sameLoopbackIgnoringPort(candidate, requested)) {
                return true;
            }
        }
        return false;
    }

    /** What the consent screen names as the destination: the host a code will be sent to. */
    public static String hostOf(String uri) {
        try {
            URI parsed = new URI(uri);
            return parsed.getHost();
        } catch (URISyntaxException e) {
            return uri;
        }
    }

    private static boolean sameLoopbackIgnoringPort(String registered, String requested) {
        try {
            URI a = new URI(registered);
            URI b = new URI(requested);
            return "http".equalsIgnoreCase(a.getScheme()) && "http".equalsIgnoreCase(b.getScheme())
                    && isLoopback(a) && isLoopback(b)
                    && a.getHost().equalsIgnoreCase(b.getHost())
                    && Objects.equals(a.getRawPath(), b.getRawPath())
                    && Objects.equals(a.getRawQuery(), b.getRawQuery())
                    && b.getRawFragment() == null && b.getRawUserInfo() == null;
        } catch (URISyntaxException e) {
            return false;
        }
    }

    private static boolean isLoopback(URI uri) {
        return uri.getHost() != null && LOOPBACK_HOSTS.contains(uri.getHost().toLowerCase(Locale.ROOT));
    }
}

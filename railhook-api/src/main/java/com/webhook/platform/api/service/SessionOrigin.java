package com.webhook.platform.api.service;

import com.webhook.platform.api.domain.enums.SessionClient;

/**
 * Where a sign-in came from, as far as the request can tell.
 *
 * <p>Both fields are decoration for a human reading their own session list, never an
 * authorization input: a User-Agent is whatever the client typed, and an IP is whatever
 * {@code TrustedProxyResolver} could establish. Nothing may be gated on either.
 */
public record SessionOrigin(SessionClient client, String userAgent, String ipAddress) {

    /** User-Agent strings are unbounded; the column is not. */
    private static final int MAX_USER_AGENT = 512;

    public static SessionOrigin of(SessionClient client, String userAgent, String ipAddress) {
        return new SessionOrigin(client, truncate(userAgent), ipAddress);
    }

    private static String truncate(String userAgent) {
        if (userAgent == null) {
            return null;
        }
        return userAgent.length() <= MAX_USER_AGENT ? userAgent : userAgent.substring(0, MAX_USER_AGENT);
    }
}

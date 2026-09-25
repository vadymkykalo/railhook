package com.webhook.platform.api.service;

import com.webhook.platform.api.domain.enums.SessionClient;

/** Display only, never an authorization input: both fields are whatever the client claims. */
public record SessionOrigin(SessionClient client, String userAgent, String ipAddress) {

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

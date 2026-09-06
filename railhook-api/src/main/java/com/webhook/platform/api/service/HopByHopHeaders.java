package com.webhook.platform.api.service;

import java.util.Set;

/** Headers that belong to one hop of a connection and must not be relayed to the next. */
public final class HopByHopHeaders {

    private static final Set<String> NAMES = Set.of(
            "connection", "keep-alive", "transfer-encoding", "te",
            "trailer", "upgrade", "proxy-authorization", "proxy-authenticate");

    private HopByHopHeaders() {
    }

    public static boolean contains(String name) {
        return NAMES.contains(name.toLowerCase());
    }
}

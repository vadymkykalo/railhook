package com.webhook.platform.api.domain.enums;

public enum DeviceAuthStatus {
    PENDING,
    APPROVED,
    DENIED,
    EXPIRED,
    /**
     * Terminal state set the moment an APPROVED code is successfully exchanged for a
     * token pair. Makes the code single-use: {@code pollDeviceToken} moves
     * APPROVED -> CONSUMED with a conditional UPDATE (compare-and-set on the old
     * status), so a second concurrent poll of the same code cannot also win.
     */
    CONSUMED
}

package com.webhook.platform.api.dto;

import com.webhook.platform.api.domain.enums.SessionClient;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * One signed-in device, as a person needs to see it in order to decide whether it is theirs.
 *
 * <p>Carries no token material of any kind — not the refresh jti, not a prefix of it. The list
 * is readable by anything holding an access token for the account, so it must not be a place
 * where a stolen access token can be upgraded into a longer-lived credential.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionResponse {

    private UUID id;

    /** WEB for a browser sign-in, CLI for a device-code grant from the command line. */
    private SessionClient client;

    /** The raw User-Agent as sent; the UI is what turns it into "Firefox on macOS". */
    private String userAgent;

    private String ipAddress;

    private Instant createdAt;

    private Instant lastSeenAt;

    private Instant expiresAt;

    /** True for the session making the request, which the UI must not offer to revoke silently. */
    private boolean current;
}

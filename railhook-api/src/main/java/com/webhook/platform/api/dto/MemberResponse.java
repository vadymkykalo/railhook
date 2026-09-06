package com.webhook.platform.api.dto;

import com.webhook.platform.api.domain.enums.MembershipRole;
import com.webhook.platform.api.domain.enums.MembershipStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemberResponse {
    private UUID userId;
    private String email;
    private MembershipRole role;
    private MembershipStatus status;
    private Instant createdAt;

    /** When a pending invite stops being accepted. Null once the invite is accepted. */
    private Instant inviteExpiresAt;

    /**
     * The accept-invite link, returned only to the owner who just issued or re-issued
     * the invite, and never on a listing — the token behind it is the credential.
     * Present because with {@code app.email.enabled=false}, the shipped default, no
     * invite mail is sent and this is the only copy that reaches a person.
     */
    private String inviteUrl;
}

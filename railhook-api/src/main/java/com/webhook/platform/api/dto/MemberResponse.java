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

    private Instant inviteExpiresAt;

    /**
     * Only in the response to issuing the invite, never on a listing: the token in it is the
     * credential. With email disabled it is the only copy anyone gets.
     */
    private String inviteUrl;
}

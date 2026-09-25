package com.webhook.platform.api.dto;

import com.webhook.platform.api.domain.enums.MembershipRole;
import com.webhook.platform.api.domain.enums.MembershipStatus;
import com.webhook.platform.api.domain.enums.UserStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminMemberResponse {

    private UUID userId;
    private String email;
    private String fullName;
    private MembershipRole role;
    private MembershipStatus membershipStatus;
    private boolean emailVerified;
    private UserStatus userStatus;
    /** {@code PASSWORD} plus one entry per linked identity provider. */
    private List<String> signInMethods;
    private Instant joinedAt;
    private Instant lastSeenAt;
    /** Same rule the admin API applies: listed in PLATFORM_ADMIN_EMAILS, verified and active. */
    private boolean platformAdmin;
}

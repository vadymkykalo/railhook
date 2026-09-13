package com.webhook.platform.api.dto;

import com.webhook.platform.api.domain.enums.MembershipRole;
import com.webhook.platform.api.domain.enums.UserStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One account on this deployment, as the platform admin sees it.
 *
 * <p>Built field by field from the user row, never serialized from it: the row also holds the
 * password hash and the verification and reset tokens, and none of those leaves the server.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminUserResponse {

    private UUID id;
    private String email;
    private String fullName;
    private boolean emailVerified;
    private UserStatus status;
    /** {@code PASSWORD}, and one entry per linked identity provider (e.g. {@code GOOGLE}). */
    private List<String> signInMethods;
    private List<OrganizationMembership> organizations;
    private Instant createdAt;
    /** The most recent activity on any of the account's sessions; null when it has none left. */
    private Instant lastSeenAt;
    /**
     * Listed in PLATFORM_ADMIN_EMAILS, verified and active: the account half of the rule the admin
     * API itself applies, so the panel marks exactly the people it would let in.
     */
    private boolean platformAdmin;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OrganizationMembership {
        private UUID id;
        private String name;
        private MembershipRole role;
    }
}

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
 * Built field by field, never serialized from the user row, which holds the password hash and
 * reset tokens.
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
    private List<String> signInMethods;
    private List<OrganizationMembership> organizations;
    private Instant createdAt;
    /** Null when the account has no sessions left. */
    private Instant lastSeenAt;
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

package com.webhook.platform.api.dto;

import com.webhook.platform.api.domain.enums.UserStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** One recent account, as the platform admin's sign-up feed shows it. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminSignupResponse {

    private UUID userId;
    private String email;
    private String fullName;
    private boolean emailVerified;
    private UserStatus status;
    /** {@code PASSWORD}, and one entry per linked identity provider (e.g. {@code GOOGLE}). */
    private List<String> signInMethods;
    /** The first organization the account joined — normally the one it created on sign-up. */
    private UUID organizationId;
    private String organizationName;
    private Instant createdAt;
}

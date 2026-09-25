package com.webhook.platform.api.dto;

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
public class AdminSignupResponse {

    private UUID userId;
    private String email;
    private String fullName;
    private boolean emailVerified;
    private UserStatus status;
    private List<String> signInMethods;
    /** The first organization the account joined. */
    private UUID organizationId;
    private String organizationName;
    private Instant createdAt;
}

package com.webhook.platform.api.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * The one place a caller supplies the organization. It is safe only because no token is minted
 * until a Membership joining this user to it is found.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SwitchOrganizationRequest {

    @NotNull(message = "organizationId is required")
    private UUID organizationId;
}

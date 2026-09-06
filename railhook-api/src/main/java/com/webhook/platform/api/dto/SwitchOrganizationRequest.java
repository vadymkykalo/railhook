package com.webhook.platform.api.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Which organization the caller wants their next token scoped to.
 *
 * <p>The organization arrives as caller input here, which is the one place in the API where that
 * is the point rather than a smell: everywhere else the tenant is derived from the token and a
 * caller-supplied organization would be a way to reach someone else's rows. What makes it safe
 * is not that it is validated as a UUID but that {@code AuthService.switchOrganization} mints
 * nothing until it has found a {@code Membership} joining this user to this organization — see
 * {@code OrganizationSwitchTest}, which is mostly about the ways that must fail.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SwitchOrganizationRequest {

    @NotNull(message = "organizationId is required")
    private UUID organizationId;
}

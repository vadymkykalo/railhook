package com.webhook.platform.api.dto;

import com.webhook.platform.api.domain.enums.MembershipRole;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChangeMemberRoleRequest {
    @NotNull(message = "Role is required")
    private MembershipRole role;
}

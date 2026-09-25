package com.webhook.platform.api.dto;

import com.webhook.platform.api.domain.enums.MembershipRole;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CurrentUserResponse {
    private UserResponse user;
    private OrganizationResponse organization;
    private MembershipRole role;
    private boolean emailDeliveryEnabled;
    // False for a Google account that never set a password.
    private boolean hasPassword;
    // UI hints only: the server checks admin and demo access on every request.
    private boolean platformAdmin;
    private boolean demo;
}

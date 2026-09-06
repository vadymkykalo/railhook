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

    /**
     * Whether this deployment can actually deliver mail. False is the shipped default,
     * and it changes what the product may promise: an invite is not "sent", a link has
     * to be passed on by hand. The flag rides on the session the UI already loads
     * rather than on an endpoint of its own.
     */
    private boolean emailDeliveryEnabled;
}

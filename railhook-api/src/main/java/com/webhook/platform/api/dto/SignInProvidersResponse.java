package com.webhook.platform.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** The identity providers the sign-in and registration pages may offer on this deployment. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SignInProvidersResponse {

    /** "Continue with Google" is configured: GOOGLE_OAUTH_CLIENT_ID and GOOGLE_OAUTH_CLIENT_SECRET are set. */
    private boolean google;
}

package com.webhook.platform.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SuspendOrganizationRequest {

    /**
     * Required, because the tenant is shown it. A suspension with no stated reason turns every
     * refusal into a support ticket asking what happened.
     */
    @NotBlank(message = "A reason is required — the suspended tenant is shown it")
    @Size(max = 500)
    private String reason;

    /**
     * Who is doing this. Optional and free text: the platform-admin credential is one shared
     * token with no identity of its own, so this is an honest record of what the operator
     * chose to write down rather than an authenticated claim.
     */
    @Size(max = 200)
    private String suspendedBy;
}

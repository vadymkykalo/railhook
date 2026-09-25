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

    @NotBlank(message = "A reason is required — the suspended tenant is shown it")
    @Size(max = 500)
    private String reason;

    // Free text, not an authenticated identity: the operator token is shared.
    @Size(max = 200)
    private String suspendedBy;
}

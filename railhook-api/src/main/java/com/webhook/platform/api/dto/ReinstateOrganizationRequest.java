package com.webhook.platform.api.dto;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** The reason goes to the audit log, not the organization. Optional so scripts can omit the body. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReinstateOrganizationRequest {

    @Size(max = 500)
    private String reason;
}

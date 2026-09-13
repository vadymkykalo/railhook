package com.webhook.platform.api.dto;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Why a suspension is being lifted.
 *
 * <p>Optional in the API, so an operator script that reinstates with no body keeps working; the
 * panel always sends one. The reason is not stored on the organization — a reinstated
 * organization has no suspension left to describe — but in the audit row the reinstatement
 * writes, which is where "who let them back in, and why" gets asked.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReinstateOrganizationRequest {

    @Size(max = 500)
    private String reason;
}

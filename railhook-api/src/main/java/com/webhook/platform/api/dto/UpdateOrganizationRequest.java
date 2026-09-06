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
public class UpdateOrganizationRequest {
    @NotBlank(message = "Organization name is required")
    @Size(min = 2, max = 100, message = "Organization name must be 2-100 characters")
    private String name;
}

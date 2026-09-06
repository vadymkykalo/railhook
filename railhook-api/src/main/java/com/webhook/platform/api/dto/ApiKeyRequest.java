package com.webhook.platform.api.dto;

import com.webhook.platform.api.domain.enums.ApiKeyScope;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApiKeyRequest {
    @NotBlank(message = "API key name is required")
    @Size(min = 2, max = 100, message = "API key name must be 2-100 characters")
    private String name;

    private ApiKeyScope scope;

    private Instant expiresAt;
}

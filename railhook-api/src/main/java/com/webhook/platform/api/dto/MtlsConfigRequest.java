package com.webhook.platform.api.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MtlsConfigRequest {

    @NotBlank(message = "Client certificate is required")
    private String clientCert;

    @NotBlank(message = "Client key is required")
    private String clientKey;

    private String caCert;
}

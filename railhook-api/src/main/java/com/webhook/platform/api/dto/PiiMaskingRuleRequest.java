package com.webhook.platform.api.dto;

import com.webhook.platform.api.domain.enums.MaskStyle;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PiiMaskingRuleRequest {

    @NotBlank(message = "Pattern name is required")
    @Size(max = 100)
    private String patternName;

    @Size(max = 500)
    private String jsonPath;

    @NotNull(message = "Mask style is required")
    private MaskStyle maskStyle;

    private Boolean enabled;
}

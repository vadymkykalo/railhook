package com.webhook.platform.api.dto;

import com.webhook.platform.api.domain.enums.CompatibilityMode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EventSchemaVersionRequest {

    @NotBlank(message = "JSON Schema is required")
    private String schemaJson;

    @Schema(description = "Compatibility promise checked against the previous version. Defaults to "
            + "the previous version's mode, or NONE for the first version.")
    private CompatibilityMode compatibilityMode;

    private String description;
}

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

    /**
     * The promise this version makes about the one before it, and the rule it is checked against
     * before being stored. Omitted, it inherits whatever the previous version declared — a project
     * that asked for BACKWARD once keeps it until it says otherwise — and NONE for a first version.
     *
     * <p>Typed rather than a free string: an unrecognised value used to be swallowed and silently
     * become NONE, so a caller who wrote "BACKWARDS" got no check and no complaint.
     */
    @Schema(description = "Compatibility promise checked against the previous version. Defaults to "
            + "the previous version's mode, or NONE for the first version.")
    private CompatibilityMode compatibilityMode;

    private String description;
}

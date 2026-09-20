package com.webhook.platform.api.dto;

import com.webhook.platform.common.transform.TransformationKind;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "One published version of a transformation's template")
public class TransformationVersionResponse {

    private UUID id;

    private UUID transformationId;

    @Schema(description = "The version number this template was published as", example = "3")
    private Integer version;

    @Schema(description = "The template itself. Omitted from the list of versions — fetch one version to read it.")
    private String template;

    @Schema(description = "The language this version was published in. Restoring it puts the language back as well as the text, so a restore cannot leave a script in a row marked TEMPLATE or the other way round.",
            example = "TEMPLATE")
    private TransformationKind kind;

    @Schema(description = "Whether this is the version the transformation is currently using")
    private boolean current;

    @Schema(description = "Set when this version was published by restoring an earlier one, naming that earlier version",
            example = "1")
    private Integer restoredFromVersion;

    @Schema(description = "The user who published it. Null when it was published with an API key, or the user has since been erased.")
    private UUID createdBy;

    @Schema(description = "Email of the user who published it, resolved at read time so an erasure takes the name and leaves the change")
    private String createdByEmail;

    private Instant createdAt;
}

package com.webhook.platform.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** Counted over the whole project, not over one page of the list. */
@Schema(description = "Unresolved incident counts for a project")
public record IncidentCountsResponse(

        @Schema(description = "Incidents that are not resolved — OPEN and INVESTIGATING together")
        long count,

        @Schema(description = "Incidents someone is actively working")
        long investigating,

        @Schema(description = "Unresolved incidents at CRITICAL severity")
        long critical
) {
}

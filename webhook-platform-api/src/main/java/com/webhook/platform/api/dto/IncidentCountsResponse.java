package com.webhook.platform.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The three numbers the incidents page leads with, each counted over the whole project.
 *
 * <p>Was a bare {@code Map.of("count", …)} carrying only the first of them, so the page derived
 * the other two from the rows it had — which is one page of a filtered list. Three tiles side by
 * side answering at three different scopes is not a display bug: a project with more open
 * incidents than fit on a page showed "Critical: 0" with a critical incident open on page two.
 *
 * <p>{@code count} keeps its name and meaning so existing callers of {@code /open-count} read
 * the same field they always did.
 */
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

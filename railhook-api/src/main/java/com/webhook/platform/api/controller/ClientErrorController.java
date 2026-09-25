package com.webhook.platform.api.controller;

import com.webhook.platform.api.dto.ClientErrorReportRequest;
import com.webhook.platform.api.security.AccessLevel;
import com.webhook.platform.api.security.AuthContext;
import com.webhook.platform.api.security.RequireAccess;
import com.webhook.platform.api.service.ClientErrorReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Requires a session so the public internet has no unauthenticated path that appends
 * attacker-controlled text to the logs. READ because a Viewer's dashboard breaks as often as an
 * Owner's.
 */
@RestController
@RequestMapping("/api/v1/client-errors")
@Tag(name = "Client Errors", description = "Failures reported by the dashboard")
@SecurityRequirement(name = "bearerAuth")
@RequiredArgsConstructor
public class ClientErrorController {

    private final ClientErrorReportService clientErrorReportService;

    @Operation(operationId = "reportClientError", summary = "Report a dashboard error",
            description = "Records a failure the dashboard could not recover from, in this "
                    + "installation's own logs. Always answers 202, whether or not the report was kept.")
    @ApiResponse(responseCode = "202", description = "Report accepted. Whether it was kept "
            + "depends on the operator's configuration and the per-user rate limit; a page that "
            + "has already failed can do nothing useful with the difference.")
    @RequireAccess(AccessLevel.READ)
    @PostMapping
    public ResponseEntity<Void> report(@Valid @RequestBody ClientErrorReportRequest request, AuthContext auth) {
        // No @RequireScope: API keys are rejected outright.
        auth.requireJwt();
        clientErrorReportService.record(request, auth.userId());
        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }
}

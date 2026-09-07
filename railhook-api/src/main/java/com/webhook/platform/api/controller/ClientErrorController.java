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
 * Where the dashboard reports a failure it could not recover from.
 *
 * <p>The reports go to this installation's own logs and nowhere else — see
 * {@link ClientErrorReportService} for what happens to them and why each precaution is there.
 *
 * <p>Two decisions worth stating, since neither is obvious from the signature:
 *
 * <p><b>It requires a session.</b> The reporter is the dashboard, and the dashboard is behind a
 * login, so the reports worth having come from someone signed in. Leaving it open would put an
 * unauthenticated write-shaped path on the public internet whose entire job is to append
 * attacker-influenced text to a log — a poor trade for the few errors on the sign-in screen,
 * which stay in the browser console. {@code READ} rather than {@code WRITE} because a Viewer's
 * dashboard breaks exactly as often as an Owner's, and nothing here changes state.
 *
 * <p><b>It always answers 202.</b> Rate-limited, disabled by the operator, or written to the
 * log: the caller is a page that has already failed once, and there is nothing useful it could
 * do with the difference.
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
        // An API key is a program; it has no dashboard to break. Rejecting it outright is
        // stronger than any scope it could hold, which is why this handler declares no
        // @RequireScope — see MutatingHandlerScopeDeclarationTest's exemption list.
        auth.requireJwt();
        clientErrorReportService.record(request, auth.userId());
        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }
}

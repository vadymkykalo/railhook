package com.webhook.platform.api.controller;

import com.webhook.platform.api.dto.ChangeEmailRequest;
import com.webhook.platform.api.dto.EmailChangeResponse;
import com.webhook.platform.api.security.AuthContext;
import com.webhook.platform.api.security.TrustedProxyResolver;
import com.webhook.platform.api.service.AuthRateLimiterService;
import com.webhook.platform.api.service.EmailChangeService;
import com.webhook.platform.api.service.VerificationMailBudget;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * The address an account signs in with. {@link EmailChangeService} has the rules; this adds the
 * per-address and per-IP limits that sit in front of every auth form.
 */
@RestController
@RequestMapping("/api/v1/auth/email-change")
@Tag(name = "Authentication")
public class EmailChangeController {

    private final EmailChangeService emailChangeService;
    private final AuthRateLimiterService authRateLimiterService;
    private final VerificationMailBudget budget;
    private final TrustedProxyResolver trustedProxyResolver;

    public EmailChangeController(EmailChangeService emailChangeService,
                                 AuthRateLimiterService authRateLimiterService,
                                 VerificationMailBudget budget,
                                 TrustedProxyResolver trustedProxyResolver) {
        this.emailChangeService = emailChangeService;
        this.authRateLimiterService = authRateLimiterService;
        this.budget = budget;
        this.trustedProxyResolver = trustedProxyResolver;
    }

    @Operation(summary = "Get the email change state",
            description = "The account's address, and the change waiting for confirmation if there is one")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping
    public ResponseEntity<EmailChangeResponse> current(AuthContext auth) {
        return ResponseEntity.ok(emailChangeService.current(auth.requireUserId()));
    }

    @Operation(summary = "Change email",
            description = "An unverified account moves to the new address at once and must verify it; it "
                    + "answers the registration CAPTCHA when one is configured. A verified account re-enters "
                    + "its password (or, with no password, has signed in within 10 minutes) and keeps its "
                    + "address until the link sent to the new one is opened.")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Changed, or waiting for confirmation"),
            @ApiResponse(responseCode = "400", description = "Wrong password, failed CAPTCHA or invalid address"),
            @ApiResponse(responseCode = "403", description = "A passwordless account has not signed in recently"),
            @ApiResponse(responseCode = "409", description = "Email already exists"),
            @ApiResponse(responseCode = "429", description = "Too many requests or daily cap reached")
    })
    @PostMapping
    public ResponseEntity<EmailChangeResponse> request(
            @Valid @RequestBody ChangeEmailRequest request,
            @CookieValue(value = "refresh_token", required = false) String cookieRefreshToken,
            AuthContext auth,
            HttpServletRequest httpRequest) {
        String clientIp = trustedProxyResolver.resolve(httpRequest);
        if (!authRateLimiterService.allowLogin(clientIp, request.getNewEmail())) {
            budget.recordRateLimited(auth.requireUserId(), "per-ip-and-address");
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many requests. Try again later.");
        }
        return ResponseEntity.ok(emailChangeService.requestChange(
                auth.requireUserId(), request, cookieRefreshToken, clientIp));
    }

    @Operation(summary = "Resend the email change confirmation")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/resend")
    public ResponseEntity<EmailChangeResponse> resend(AuthContext auth) {
        return ResponseEntity.ok(emailChangeService.resend(auth.requireUserId()));
    }

    @Operation(summary = "Cancel the pending email change")
    @SecurityRequirement(name = "bearerAuth")
    @DeleteMapping
    public ResponseEntity<Void> cancel(AuthContext auth) {
        emailChangeService.cancel(auth.requireUserId());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Confirm an email change",
            description = "Opened from the link sent to the new address. Signs every session out.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Email changed"),
            @ApiResponse(responseCode = "400", description = "Invalid, used or expired link"),
            @ApiResponse(responseCode = "409", description = "Email already exists")
    })
    @PostMapping("/confirm")
    public ResponseEntity<Void> confirm(@RequestParam("token") String token, HttpServletRequest httpRequest) {
        requireTokenAllowance(token, httpRequest);
        emailChangeService.confirm(token);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "Cancel an email change from the old address",
            description = "Opened from the \"this wasn't me\" link sent to the old address. Signs every session out.")
    @PostMapping("/cancel")
    public ResponseEntity<Void> cancelByToken(@RequestParam("token") String token, HttpServletRequest httpRequest) {
        requireTokenAllowance(token, httpRequest);
        emailChangeService.cancelByToken(token);
        return ResponseEntity.ok().build();
    }

    private void requireTokenAllowance(String token, HttpServletRequest httpRequest) {
        if (!authRateLimiterService.allowTokenAction(trustedProxyResolver.resolve(httpRequest), token)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many requests. Try again later.");
        }
    }
}

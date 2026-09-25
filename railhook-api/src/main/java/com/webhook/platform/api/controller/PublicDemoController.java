package com.webhook.platform.api.controller;

import com.webhook.platform.api.dto.DemoSessionRequest;
import com.webhook.platform.api.dto.DemoSessionResponse;
import com.webhook.platform.api.security.AllowedInDemo;
import com.webhook.platform.api.security.ProjectScopeExempt;
import com.webhook.platform.api.security.TrustedProxyResolver;
import com.webhook.platform.api.service.AuthRateLimiterService;
import com.webhook.platform.api.service.DemoSessionService;
import com.webhook.platform.api.service.captcha.CaptchaVerifier;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/public/demo")
@Tag(name = "Demo", description = "Read-only sessions in the public demo organization")
@ProjectScopeExempt(reason = "public and anonymous; a demo session is opened before any project is known")
@RequiredArgsConstructor
public class PublicDemoController {

    private final DemoSessionService demoSessionService;
    private final AuthRateLimiterService authRateLimiterService;
    private final TrustedProxyResolver trustedProxyResolver;
    private final CaptchaVerifier captchaVerifier;

    @Operation(operationId = "createDemoSession", summary = "Open a demo session",
            description = "A short-lived, read-only access token for the demo organization. It cannot be refreshed, "
                    + "and every request that would change something is refused with 403 `demo_read_only`. "
                    + "Ten sessions a minute per address, behind the registration challenge where one is configured.")
    @ApiResponse(responseCode = "200", description = "The session",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = DemoSessionResponse.class)))
    @ApiResponse(responseCode = "400", description = "The challenge was not passed")
    @ApiResponse(responseCode = "404", description = "The demo is not enabled on this server")
    @ApiResponse(responseCode = "429", description = "Too many demo sessions from this address")
    @ApiResponse(responseCode = "503", description = "The demo data is still being prepared")
    @AllowedInDemo(reason = "opens a new demo session; a visitor whose demo expired must be able to start another")
    @PostMapping("/session")
    public ResponseEntity<?> createSession(@RequestBody(required = false) DemoSessionRequest body,
                                           HttpServletRequest request) {
        // First, so with the demo off the path looks like it does not exist.
        demoSessionService.requireEnabled();
        String ip = trustedProxyResolver.resolve(request);
        if (!authRateLimiterService.allowDemoSession(ip)) {
            return error(HttpStatus.TOO_MANY_REQUESTS, "rate_limit_exceeded", "Too many demo sessions. Try again in a minute.");
        }
        if (!captchaVerifier.verify(body == null ? null : body.getCaptchaToken(), ip)) {
            return error(HttpStatus.BAD_REQUEST, "captcha_failed", "Challenge verification failed. Please try again.");
        }
        DemoSessionResponse session = demoSessionService.open();
        return ResponseEntity.ok(session);
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of("error", code, "message", message));
    }
}

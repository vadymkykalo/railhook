package com.webhook.platform.api.controller;

import com.webhook.platform.api.dto.PublicBinResponse;
import com.webhook.platform.api.security.ProjectScopeExempt;
import com.webhook.platform.api.security.TrustedProxyResolver;
import com.webhook.platform.api.service.AuthRateLimiterService;
import com.webhook.platform.api.service.PublicBinService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The webhook tester on the public site, with no account: make a URL, then read what it received.
 * Requests are sent to the URL itself, which {@link PublicBinCaptureController} answers.
 */
@RestController
@RequestMapping("/api/v1/public/bins")
@Tag(name = "Webhook Tester", description = "Public webhook tester URLs that need no account")
@ProjectScopeExempt(reason = "public and anonymous; a tester URL belongs to no project")
@RequiredArgsConstructor
public class PublicBinController {

    private final PublicBinService publicBinService;
    private final AuthRateLimiterService authRateLimiterService;
    private final TrustedProxyResolver trustedProxyResolver;

    @Operation(operationId = "createPublicBin", summary = "Make a tester URL",
            description = "A URL that records every request sent to it for a day: the latest 100, with the first "
                    + "64 KB of each body and credentials masked. Anyone who has the URL can read what it received.")
    @ApiResponse(responseCode = "201", description = "The URL")
    @ApiResponse(responseCode = "429", description = "Too many URLs made from this address")
    @PostMapping
    public ResponseEntity<PublicBinResponse> create(HttpServletRequest request) {
        if (!authRateLimiterService.allowPublicBin(trustedProxyResolver.resolve(request))) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(publicBinService.create());
    }

    @Operation(operationId = "getPublicBin", summary = "Read a tester URL",
            description = "The URL and the requests it received, newest first.")
    @ApiResponse(responseCode = "200", description = "The URL and its requests")
    @ApiResponse(responseCode = "404", description = "No such URL, or it has expired")
    @GetMapping("/{slug}")
    public ResponseEntity<PublicBinResponse> read(@PathVariable("slug") String slug) {
        return ResponseEntity.ok(publicBinService.read(slug));
    }
}

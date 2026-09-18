package com.webhook.platform.api.controller;

import com.webhook.platform.api.security.ProjectScopeExempt;
import com.webhook.platform.api.service.PublicBinService;
import com.webhook.platform.api.service.RedisRateLimiterService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Map;

import static com.webhook.platform.api.filter.IngressRawBodyFilter.rawBody;

/**
 * A public tester URL receiving a request. Under /hook/, so nginx already proxies it, the raw body
 * is kept as it arrived, and it is public; {@code /hook/{slug}} is a project's test endpoint, this
 * is {@code /hook/p/{slug}}.
 */
@RestController
@RequestMapping("/hook/p")
@Tag(name = "Webhook Tester", description = "Public webhook tester URLs that need no account")
@ProjectScopeExempt(reason = "public and anonymous; a tester URL belongs to no project")
@RequiredArgsConstructor
public class PublicBinCaptureController {

    /** The same ceiling as a project's test endpoint. */
    private static final int RATE_LIMIT_PER_SECOND = 10;
    private static final String RATE_KEY_PREFIX = "public:";

    private final PublicBinService publicBinService;
    private final RedisRateLimiterService rateLimiterService;

    @Operation(operationId = "capturePublicBinRequest", summary = "Send a request to a tester URL",
            description = "Any method, any body. Recorded and answered with 200.")
    @RequestBody(
            content = @Content(mediaType = "application/json", schema = @Schema(type = "string")))
    @ApiResponse(responseCode = "200", description = "Recorded")
    @ApiResponse(responseCode = "404", description = "No such URL, or it has expired")
    @ApiResponse(responseCode = "429", description = "Too many requests to this URL")
    @RequestMapping(value = "/{slug}", method = { RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT,
            RequestMethod.PATCH, RequestMethod.DELETE })
    public ResponseEntity<Map<String, Object>> capture(@PathVariable("slug") String slug, HttpServletRequest request)
            throws IOException {
        byte[] body = rawBody(request);
        if (!rateLimiterService.tryAcquireForSlug(RATE_KEY_PREFIX + slug, RATE_LIMIT_PER_SECOND)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("ok", false, "error", "rate_limit_exceeded"));
        }
        long id = publicBinService.capture(slug, body, request);
        return ResponseEntity.ok(Map.of("ok", true, "requestId", id));
    }
}

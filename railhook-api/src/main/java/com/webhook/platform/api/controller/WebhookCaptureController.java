package com.webhook.platform.api.controller;

import com.webhook.platform.api.domain.repository.TestEndpointRepository;
import com.webhook.platform.api.dto.CapturedRequestResponse;
import com.webhook.platform.api.dto.WebhookCaptureResponse;
import com.webhook.platform.api.service.RedisRateLimiterService;
import com.webhook.platform.api.service.TestEndpointService;
import com.webhook.platform.api.tenancy.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static com.webhook.platform.api.filter.IngressRawBodyFilter.rawBody;

@Slf4j
@RestController
@RequestMapping("/hook")
@Tag(name = "Webhook Capture", description = "Public endpoints to receive and capture webhook requests")
public class WebhookCaptureController {

    private static final int RATE_LIMIT_PER_SECOND = 10;

    private final TestEndpointService testEndpointService;
    private final TestEndpointRepository testEndpointRepository;
    private final ObjectMapper objectMapper;
    private final RedisRateLimiterService rateLimiterService;

    public WebhookCaptureController(TestEndpointService testEndpointService,
                                    TestEndpointRepository testEndpointRepository,
                                    ObjectMapper objectMapper,
                                    RedisRateLimiterService rateLimiterService) {
        this.testEndpointService = testEndpointService;
        this.testEndpointRepository = testEndpointRepository;
        this.objectMapper = objectMapper;
        this.rateLimiterService = rateLimiterService;
    }

    @RequestMapping(value = "/{slug}", method = { RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT,
            RequestMethod.PATCH, RequestMethod.DELETE })
    @Operation(summary = "Capture webhook request", description = "Captures any HTTP request sent to this test endpoint")
    // Documented as the String it used to be bound as: the wire format is unchanged.
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            content = @Content(mediaType = "application/json", schema = @Schema(type = "string")))
    public ResponseEntity<WebhookCaptureResponse> captureRequest(
            @PathVariable("slug") String slug,
            HttpServletRequest request) throws IOException {
        // Not @RequestBody String: for a form POST that is a body Spring rebuilt from parsed
        // parameters, and a capture has to show what was sent. IngressRawBodyFilter kept it; it
        // is stored as text, decoded with the charset the request declares.
        byte[] raw = rawBody(request);
        String body = raw == null ? null : new String(raw, charsetOf(request));

        // Early check: reject unknown slugs before allocating a rate-limit bucket.
        // Unscoped on purpose: /hook/** is public, so nothing has established a tenant yet and
        // the slug is what identifies the endpoint's owner.
        if (!TenantContext.callAsSystem(() -> testEndpointRepository.existsBySlug(slug))) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(WebhookCaptureResponse.builder()
                            .success(false)
                            .error("not_found")
                            .message("Test endpoint not found")
                            .build());
        }

        // Rate limit per slug (10 req/s) — Redis-backed for multi-instance consistency
        if (!rateLimiterService.tryAcquireForSlug(slug, RATE_LIMIT_PER_SECOND)) {
            log.warn("Rate limit exceeded for test endpoint slug: {}", slug);
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(WebhookCaptureResponse.builder()
                            .success(false)
                            .error("rate_limit_exceeded")
                            .message("Too many requests to this test endpoint")
                            .build());
        }

        // Auto-respond to verification challenges BEFORE slug lookup
        // so verification works even if the test endpoint expired or was deleted
        if (body != null && !body.isEmpty()) {
            try {
                JsonNode json = objectMapper.readTree(body);
                if (json.has("type") && "webhook.verification".equals(json.get("type").asText())) {
                    String challenge = json.has("challenge") ? json.get("challenge").asText() : null;
                    if (challenge != null) {
                        log.info("Endpoint /hook/{} responding to verification challenge", slug);
                        return ResponseEntity.ok(WebhookCaptureResponse.builder()
                                .success(true)
                                .message("Verification challenge accepted")
                                .challenge(challenge)
                                .build());
                    }
                }
            } catch (Exception e) {
                // Not JSON or parsing failed, continue with normal capture flow
            }
        }

        // The body was read once, above; the request stream is spent and cannot give it again.
        CapturedRequestResponse captured = testEndpointService.captureRequest(slug, body, request);

        return ResponseEntity.ok(WebhookCaptureResponse.builder()
                .success(true)
                .message("Request captured")
                .requestId(captured.getId())
                .receivedAt(captured.getReceivedAt() != null ? captured.getReceivedAt().toString() : Instant.now().toString())
                .build());
    }

    private static Charset charsetOf(HttpServletRequest request) {
        String encoding = request.getCharacterEncoding();
        if (encoding == null) {
            return StandardCharsets.UTF_8;
        }
        try {
            return Charset.forName(encoding);
        } catch (IllegalArgumentException e) {
            return StandardCharsets.UTF_8;
        }
    }
}

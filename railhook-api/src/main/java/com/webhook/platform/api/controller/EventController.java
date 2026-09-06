package com.webhook.platform.api.controller;

import com.webhook.platform.api.domain.enums.ApiKeyScope;
import com.webhook.platform.api.dto.EventIngestRequest;
import com.webhook.platform.api.dto.EventIngestResponse;
import com.webhook.platform.api.dto.RateLimitInfo;
import com.webhook.platform.api.dto.RateLimitResult;
import com.webhook.platform.api.security.ApiKeyAuthenticationToken;
import com.webhook.platform.api.security.RequireScope;
import com.webhook.platform.api.service.EventIngestService;
import com.webhook.platform.api.service.billing.EntitlementService;
import com.webhook.platform.api.service.billing.QuotaType;
import com.webhook.platform.api.service.billing.RequireQuota;
import com.webhook.platform.api.service.RedisRateLimiterService;
import com.webhook.platform.api.exception.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/events")
@Slf4j
@Tag(name = "Events", description = "Event ingestion API")
public class EventController {

    private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    private final EventIngestService eventIngestService;
    private final RedisRateLimiterService rateLimiterService;
    private final EntitlementService entitlementService;

    public EventController(
            EventIngestService eventIngestService,
            RedisRateLimiterService rateLimiterService,
            EntitlementService entitlementService) {
        this.eventIngestService = eventIngestService;
        this.rateLimiterService = rateLimiterService;
        this.entitlementService = entitlementService;
    }

    @Operation(
            summary = "Ingest event",
            description = "Sends an event to all subscribed webhook endpoints. Requires API Key authentication."
    )
    @SecurityRequirement(name = "apiKey")
    // The handler returns ResponseEntity<?> because a 429 carries an ErrorResponse instead, so
    // springdoc has no return type to read and published the success body as a bare object --
    // schemaWarnings and deliveriesCreated alike were invisible to anyone coding against the spec.
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Event accepted for delivery",
                    content = @Content(schema = @Schema(implementation = EventIngestResponse.class))),
            @ApiResponse(responseCode = "401", description = "Invalid or missing API key"),
            @ApiResponse(responseCode = "429", description = "Rate limit exceeded",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @RequireScope(ApiKeyScope.READ_WRITE)
    @RequireQuota(QuotaType.EVENTS_PER_MONTH)
    @PostMapping
    public ResponseEntity<?> ingestEvent(
            @Valid @RequestBody EventIngestRequest request,
            @Parameter(description = "Unique key for idempotent event ingestion")
            @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            Authentication authentication) {

        if (!(authentication instanceof ApiKeyAuthenticationToken)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        ApiKeyAuthenticationToken apiKeyAuth = (ApiKeyAuthenticationToken) authentication;

        int rateLimit = entitlementService.getRateLimitForProject(apiKeyAuth.getProjectId());
        RateLimitResult rateLimitResult = rateLimiterService.tryAcquireWithInfo(apiKeyAuth.getProjectId(), rateLimit);
        RateLimitInfo info = rateLimitResult.getInfo();
        
        if (!rateLimitResult.isAcquired()) {
            log.warn("Rate limit exceeded for project: {}", apiKeyAuth.getProjectId());
            ErrorResponse errorBody = new ErrorResponse(
                    "rate_limit_exceeded",
                    "Too many requests. Please retry after " + rateLimitResult.getRetryAfterSeconds() + " seconds.",
                    HttpStatus.TOO_MANY_REQUESTS.value()
            );
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("X-RateLimit-Limit", String.valueOf(info.getLimit()))
                    .header("X-RateLimit-Remaining", "0")
                    .header("X-RateLimit-Reset", String.valueOf(info.getResetTimestamp()))
                    .header("Retry-After", String.valueOf(rateLimitResult.getRetryAfterSeconds()))
                    .body(errorBody);
        }
        
        log.info("Ingesting event type: {} for project: {}", request.getType(), apiKeyAuth.getProjectId());

        EventIngestResponse response = eventIngestService.ingestEvent(
                apiKeyAuth.getProjectId(),
                request,
                idempotencyKey
        );

        return ResponseEntity.status(HttpStatus.CREATED)
                .header("X-RateLimit-Limit", String.valueOf(info.getLimit()))
                .header("X-RateLimit-Remaining", String.valueOf(info.getRemaining()))
                .header("X-RateLimit-Reset", String.valueOf(info.getResetTimestamp()))
                .body(response);
    }
}

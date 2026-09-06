package com.webhook.platform.api.controller;

import com.webhook.platform.api.domain.entity.IncomingEvent;
import com.webhook.platform.api.dto.IngressResponse;
import com.webhook.platform.api.exception.QuotaExceededException;
import com.webhook.platform.api.service.IngressService;
import com.webhook.platform.api.service.ingress.PayloadTooLargeException;
import com.webhook.platform.api.service.ingress.RateLimitExceededException;
import com.webhook.platform.api.service.ingress.SignatureVerificationFailedException;
import com.webhook.platform.api.service.ingress.SourceDisabledException;
import com.webhook.platform.api.service.ingress.SourceNotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/ingress")
@Slf4j
@Tag(name = "Ingress", description = "Public incoming webhook ingress endpoint")
public class IngressController {

    private final IngressService ingressService;

    public IngressController(IngressService ingressService) {
        this.ingressService = ingressService;
    }

    @Operation(summary = "Receive incoming webhook",
            description = "Public endpoint for third-party providers to send webhooks. " +
                    "The token in the path identifies the incoming source configuration.")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Webhook accepted for processing",
                    content = @Content(schema = @Schema(implementation = IngressResponse.class))),
            @ApiResponse(responseCode = "404", description = "Invalid ingress token",
                    content = @Content(schema = @Schema(implementation = IngressResponse.class))),
            @ApiResponse(responseCode = "410", description = "Source is disabled",
                    content = @Content(schema = @Schema(implementation = IngressResponse.class))),
            @ApiResponse(responseCode = "413", description = "Payload too large",
                    content = @Content(schema = @Schema(implementation = IngressResponse.class))),
            @ApiResponse(responseCode = "401", description = "Signature verification failed",
                    content = @Content(schema = @Schema(implementation = IngressResponse.class))),
            @ApiResponse(responseCode = "429", description = "Rate limit or monthly event quota exceeded",
                    content = @Content(schema = @Schema(implementation = IngressResponse.class)))
    })
    @PostMapping("/{token}")
    public ResponseEntity<IngressResponse> receiveWebhook(
            @PathVariable("token") String token,
            @RequestBody(required = false) String body,
            HttpServletRequest request) {
        IncomingEvent event = ingressService.receiveWebhook(token, body, request);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(IngressResponse.builder()
                        .status("accepted")
                        .requestId(event.getRequestId())
                        .build());
    }

    @ExceptionHandler(SourceNotFoundException.class)
    ResponseEntity<IngressResponse> sourceNotFound(SourceNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "not_found", "Invalid ingress endpoint");
    }

    @ExceptionHandler(SourceDisabledException.class)
    ResponseEntity<IngressResponse> sourceDisabled(SourceDisabledException e) {
        return problem(HttpStatus.GONE, "disabled", "This ingress endpoint is disabled");
    }

    @ExceptionHandler(PayloadTooLargeException.class)
    ResponseEntity<IngressResponse> payloadTooLarge(PayloadTooLargeException e) {
        return problem(HttpStatus.PAYLOAD_TOO_LARGE, "payload_too_large", e.getMessage());
    }

    @ExceptionHandler(RateLimitExceededException.class)
    ResponseEntity<IngressResponse> rateLimited(RateLimitExceededException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", "1")
                .body(IngressResponse.builder()
                        .error("rate_limit_exceeded")
                        .message("Too many requests. Please retry later.")
                        .build());
    }

    /**
     * 429 rather than the 402 an authenticated caller gets, and with no detail: the sender is a
     * third-party provider, not the customer, and it has no business learning which plan the
     * customer is on or how much of it they have used.
     */
    @ExceptionHandler(QuotaExceededException.class)
    ResponseEntity<IngressResponse> quotaExceeded(QuotaExceededException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(IngressResponse.builder()
                        .error("quota_exceeded")
                        .message("This endpoint is not accepting webhooks right now.")
                        .build());
    }

    @ExceptionHandler(SignatureVerificationFailedException.class)
    ResponseEntity<IngressResponse> signatureFailed(SignatureVerificationFailedException e) {
        return problem(HttpStatus.UNAUTHORIZED, "signature_verification_failed",
                "Webhook signature verification failed");
    }

    private ResponseEntity<IngressResponse> problem(HttpStatus status, String error, String message) {
        return ResponseEntity.status(status)
                .body(IngressResponse.builder().error(error).message(message).build());
    }
}

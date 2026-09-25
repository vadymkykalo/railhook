package com.webhook.platform.api.controller;

import com.webhook.platform.api.domain.entity.IncomingEvent;
import com.webhook.platform.api.dto.IngressResponse;
import com.webhook.platform.api.dto.SlackUrlVerificationResponse;
import com.webhook.platform.api.exception.QuotaExceededException;
import com.webhook.platform.api.service.IngressService;
import com.webhook.platform.api.service.ingress.IngressOutcome;
import com.webhook.platform.api.service.ingress.OrganizationSuspendedException;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

import static com.webhook.platform.api.filter.IngressRawBodyFilter.rawBody;

@RestController
@RequestMapping("/ingress")
@Slf4j
@Tag(name = "Ingress", description = "Public incoming webhook ingress endpoint")
public class IngressController {

    static final String QUOTA_RETRY_AFTER_SECONDS = "3600";

    private final IngressService ingressService;

    public IngressController(IngressService ingressService) {
        this.ingressService = ingressService;
    }

    @Operation(summary = "Receive incoming webhook",
            description = "Public endpoint for third-party providers to send webhooks. " +
                    "The token in the path identifies the incoming source configuration.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Slack url_verification handshake on a SLACK "
                    + "source, answered once its signature is verified. The challenge is echoed and "
                    + "nothing is stored or forwarded.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = SlackUrlVerificationResponse.class))),
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
    // A plain string: springdoc would document raw bytes as `format: byte`, which OpenAPI reads
    // as base64.
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "The provider's payload, exactly as they send it. Signatures are "
                    + "verified over these bytes, so nothing re-encodes them in transit.",
            content = @Content(mediaType = "application/json", schema = @Schema(type = "string")))
    @PostMapping("/{token}")
    public ResponseEntity<?> receiveWebhook(
            @PathVariable("token") String token,
            HttpServletRequest request) throws IOException {
        // Signatures are checked over the exact bytes sent. A String would be charset-decoded,
        // and @RequestBody byte[] on a form POST is rebuilt from the parsed parameters.
        IngressOutcome outcome = ingressService.receiveWebhook(token, rawBody(request), request);
        if (outcome instanceof IngressOutcome.SlackUrlVerification handshake) {
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new SlackUrlVerificationResponse(handshake.challenge()));
        }
        IncomingEvent event = ((IngressOutcome.Accepted) outcome).event();
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

    /**
     * 403, not 410: providers such as Zapier delete a subscription for good on 410, and a
     * suspension can be lifted. The reason stays out of the body because the sender is a third
     * party.
     */
    @ExceptionHandler(OrganizationSuspendedException.class)
    ResponseEntity<IngressResponse> organizationSuspended(OrganizationSuspendedException e) {
        return problem(HttpStatus.FORBIDDEN, "suspended", "This ingress endpoint is not accepting webhooks");
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
     * 429 with no detail rather than 402: the third-party sender has no business learning the
     * customer's plan. Retry-After is an hour because the customer may upgrade at any time, and a
     * wait of days would push providers past their own retry window.
     */
    @ExceptionHandler(QuotaExceededException.class)
    ResponseEntity<IngressResponse> quotaExceeded(QuotaExceededException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", QUOTA_RETRY_AFTER_SECONDS)
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

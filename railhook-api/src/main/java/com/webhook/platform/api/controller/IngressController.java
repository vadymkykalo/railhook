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

    /** How long an organization over its quota tells a provider to wait; see {@link #quotaExceeded}. */
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
    // Described as a plain string, not as what springdoc infers from byte[]. It would write
    // `format: byte`, which in OpenAPI means base64 — and a provider reading that would encode a
    // payload nobody asked them to encode. The body is read as raw bytes so the signature is
    // checked against what was sent; the wire format is unchanged and the documentation has to
    // keep saying so.
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "The provider's payload, exactly as they send it. Signatures are "
                    + "verified over these bytes, so nothing re-encodes them in transit.",
            content = @Content(mediaType = "application/json", schema = @Schema(type = "string")))
    @PostMapping("/{token}")
    public ResponseEntity<?> receiveWebhook(
            @PathVariable("token") String token,
            HttpServletRequest request) throws IOException {
        // Bytes, not a String: Spring decodes a String parameter with whatever charset the
        // Content-Type declares, and every verifier then encoded it back as UTF-8 — so a sender
        // that used anything else had its genuine signature rejected. And not @RequestBody byte[]
        // either: for a form POST that is a body Spring rebuilt from parsed parameters rather than
        // the one that was signed. IngressRawBodyFilter kept the original.
        IngressOutcome outcome = ingressService.receiveWebhook(token, rawBody(request), request);
        // Slack enables a Request URL only once it has echoed the challenge; a 202 without it
        // left a SLACK Source impossible to connect to the Events API.
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
     * 403, not the 410 a disabled Source answers: providers such as Zapier delete a subscription for
     * good on 410, and a suspension can be lifted. The reason stays out of the body, since the
     * sender is a third-party provider, not the customer it was written for.
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
     * 429 rather than the 402 an authenticated caller gets, and with no detail: the sender is a
     * third-party provider, not the customer, and it has no business learning which plan the
     * customer is on or how much of it they have used.
     *
     * <p>{@code Retry-After} is an hour, not the time until the month rolls over. The refusal ends
     * either then or the moment the customer changes plan, and nothing here can know which; days
     * would push a provider that honours the header past its own retry window, and without one it
     * guessed, and the providers that give up dropped the webhook.
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

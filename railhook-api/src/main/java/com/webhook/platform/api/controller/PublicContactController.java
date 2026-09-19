package com.webhook.platform.api.controller;

import com.webhook.platform.api.security.AllowedInDemo;
import com.webhook.platform.api.dto.PublicContactRequest;
import com.webhook.platform.api.security.ProjectScopeExempt;
import com.webhook.platform.api.security.TrustedProxyResolver;
import com.webhook.platform.api.service.AuthRateLimiterService;
import com.webhook.platform.api.service.ContactMessageBudget;
import com.webhook.platform.api.service.EmailService;
import com.webhook.platform.api.service.captcha.CaptchaVerifier;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * The message form on the public site: a visitor with no account writes to this deployment's
 * support address, and the answer goes back to the address they gave.
 */
@RestController
@RequestMapping("/api/v1/public/contact")
@Tag(name = "Contact", description = "Messages from the public site to the deployment's support address")
@ProjectScopeExempt(reason = "public and anonymous; a message to support belongs to no project")
@RequiredArgsConstructor
public class PublicContactController {

    private final EmailService emailService;
    private final AuthRateLimiterService authRateLimiterService;
    private final TrustedProxyResolver trustedProxyResolver;
    private final CaptchaVerifier captchaVerifier;
    private final ContactMessageBudget contactMessageBudget;

    @Operation(operationId = "sendContactMessage", summary = "Write to support",
            description = "Sends the message to this deployment's support address, with the given email as the "
                    + "Reply-To. Two messages a minute per address, and a daily ceiling across all senders.")
    @ApiResponse(responseCode = "202", description = "The message is on its way")
    @ApiResponse(responseCode = "400", description = "A field is invalid, or the challenge was not passed")
    @ApiResponse(responseCode = "429", description = "Too many messages from this address, or the form's daily ceiling is reached")
    @ApiResponse(responseCode = "503", description = "This deployment has no support address")
    @AllowedInDemo(reason = "anonymous by design; mails the deployment's support address and touches no tenant data")
    @PostMapping
    public ResponseEntity<Map<String, String>> send(@Valid @RequestBody PublicContactRequest body,
                                                    HttpServletRequest request) {
        if (!emailService.isContactAvailable()) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, "contact_unavailable", "This deployment has no support address.");
        }
        String ip = trustedProxyResolver.resolve(request);
        if (!authRateLimiterService.allowContactMessage(ip)) {
            return error(HttpStatus.TOO_MANY_REQUESTS, "rate_limit_exceeded", "Too many messages. Try again in a minute.");
        }
        if (!captchaVerifier.verify(body.getCaptchaToken(), ip)) {
            return error(HttpStatus.BAD_REQUEST, "captcha_failed", "Challenge verification failed. Please try again.");
        }
        // Last, so neither a refused challenge nor an over-eager address spends it: this ceiling
        // exists to keep the mail quota for verification and password-reset mails.
        if (!contactMessageBudget.tryAcquire()) {
            return error(HttpStatus.TOO_MANY_REQUESTS, "contact_busy", "The form has taken all the messages it can today. Please write to support by email.");
        }
        String topic = body.getTopic() == null || body.getTopic().isEmpty() ? "other" : body.getTopic();
        emailService.sendContactMessage(body.getEmail().strip(), body.getName(), topic, body.getMessage().strip(), body.getPage());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("status", "sent"));
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of("error", code, "message", message));
    }
}

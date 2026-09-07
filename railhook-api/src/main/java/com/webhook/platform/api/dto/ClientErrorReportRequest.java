package com.webhook.platform.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * What the dashboard sends when a render fails. Every field is attacker-controlled in the sense
 * that matters here — a browser chose it — so the sizes below are the outer bound and
 * {@code ClientErrorReportService} trims further before anything reaches a log line.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "A failure the dashboard could not recover from, reported by the browser")
public class ClientErrorReportRequest {

    @NotBlank(message = "Message is required")
    @Size(max = 2000, message = "Message must not exceed 2000 characters")
    @Schema(description = "The error message", example = "Cannot read properties of undefined")
    private String message;

    @Size(max = 20000, message = "Stack must not exceed 20000 characters")
    @Schema(description = "The JavaScript stack trace, if the browser provided one")
    private String stack;

    @Size(max = 20000, message = "Component stack must not exceed 20000 characters")
    @Schema(description = "React's component stack, which names the component that threw")
    private String componentStack;

    @Size(max = 2000, message = "URL must not exceed 2000 characters")
    @Schema(description = "The page the failure happened on. Its query string is discarded on arrival.",
            example = "https://hooks.example.com/admin/deliveries")
    private String url;

    @Size(max = 100, message = "Release must not exceed 100 characters")
    @Schema(description = "The dashboard build that produced the error", example = "2.13.0")
    private String release;
}

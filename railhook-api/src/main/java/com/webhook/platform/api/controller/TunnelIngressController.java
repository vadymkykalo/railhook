package com.webhook.platform.api.controller;

import com.webhook.platform.api.service.HopByHopHeaders;
import com.webhook.platform.api.service.TunnelIngressService;
import com.webhook.platform.common.dto.tunnel.TunnelBody;
import com.webhook.platform.common.dto.tunnel.TunnelRequestMessage;
import com.webhook.platform.common.dto.tunnel.TunnelResponseMessage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static com.webhook.platform.api.filter.IngressRawBodyFilter.rawBody;

@RestController
@RequestMapping("/tunnel")
@Tag(name = "Tunnel Ingress", description = "Public tunnel ingress endpoints")
@RequiredArgsConstructor
public class TunnelIngressController {

    private final TunnelIngressService tunnelIngressService;

    // A tunnel URL is a base: /tunnel/<slug>/webhooks/stripe reaches /webhooks/stripe locally.
    @RequestMapping(value = {"/{slug}", "/{slug}/**"}, method = {RequestMethod.GET, RequestMethod.POST,
            RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE, RequestMethod.HEAD, RequestMethod.OPTIONS})
    @Operation(summary = "Tunnel ingress", description = "Forward request through CLI tunnel to local application")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            content = @Content(mediaType = "application/json", schema = @Schema(type = "string")))
    @ApiResponse(responseCode = "200", description = "OK",
            content = @Content(mediaType = "*/*", schema = @Schema(type = "string")))
    public ResponseEntity<byte[]> handleTunnelRequest(
            @PathVariable("slug") String slug,
            HttpServletRequest request) throws IOException {
        // Raw bytes, not a String: the local app verifies the provider's signature over them.
        byte[] body = rawBody(request);

        TunnelIngressService.Outcome outcome =
                tunnelIngressService.forward(slug, asTunnelRequest(slug, body, request), body);

        if (outcome instanceof TunnelIngressService.Outcome.Answered answered) {
            return relay(answered.response());
        }
        if (outcome instanceof TunnelIngressService.Outcome.Refused refused) {
            return problem(statusFor(refused), refused.error(), refused.message());
        }
        if (outcome instanceof TunnelIngressService.Outcome.TimedOut) {
            return problem(HttpStatus.GATEWAY_TIMEOUT, "tunnel_timeout",
                    "Tunnel request timed out or tunnel disconnected");
        }
        if (outcome instanceof TunnelIngressService.Outcome.Failed failed) {
            return problem(HttpStatus.BAD_GATEWAY, "tunnel_error", failed.detail());
        }
        throw new IllegalStateException("Unhandled tunnel outcome: " + outcome);
    }

    private static HttpStatus statusFor(TunnelIngressService.Outcome.Refused refused) {
        return switch (refused.error()) {
            case "rate_limit_exceeded" -> HttpStatus.TOO_MANY_REQUESTS;
            case "payload_too_large" -> HttpStatus.PAYLOAD_TOO_LARGE;
            // A CDN replaces a 502 with its own page, and providers retry a 503.
            case "tunnel_offline" -> HttpStatus.SERVICE_UNAVAILABLE;
            case "tunnel_suspended" -> HttpStatus.FORBIDDEN;
            default -> HttpStatus.BAD_GATEWAY;
        };
    }

    private TunnelRequestMessage asTunnelRequest(String slug, byte[] body, HttpServletRequest request) {
        Map<String, String> headers = relayableHeaders(request);
        return TunnelRequestMessage.builder()
                .type("TUNNEL_REQUEST")
                .requestId(UUID.randomUUID().toString())
                .method(request.getMethod())
                .path(pathAfterSlug(request.getRequestURI(), slug))
                .queryString(request.getQueryString())
                .headers(headers)
                .rawBody(body, TunnelBody.charsetOf(headers))
                .timestampMs(System.currentTimeMillis())
                .build();
    }

    // Not replaceFirst: the slug is caller-controlled and would be read as a regex.
    private static String pathAfterSlug(String uri, String slug) {
        String prefix = "/tunnel/" + slug;
        return uri.startsWith(prefix) ? uri.substring(prefix.length()) : uri;
    }

    private Map<String, String> relayableHeaders(HttpServletRequest request) {
        Map<String, String> headers = new LinkedHashMap<>();
        Enumeration<String> names = request.getHeaderNames();
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            if (!HopByHopHeaders.contains(name) && !name.equalsIgnoreCase("host")) {
                headers.put(name, request.getHeader(name));
            }
        }
        return headers;
    }

    private ResponseEntity<byte[]> relay(TunnelResponseMessage response) {
        HttpHeaders headers = new HttpHeaders();
        if (response.getHeaders() != null) {
            response.getHeaders().forEach((name, value) -> {
                if (!HopByHopHeaders.contains(name)) {
                    headers.add(name, value);
                }
            });
        }
        return ResponseEntity.status(response.getStatusCode()).headers(headers).body(response.bodyBytes());
    }

    private ResponseEntity<byte[]> problem(HttpStatus status, String error, String message) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(("{\"error\":\"" + error + "\",\"message\":\"" + message + "\"}")
                        .getBytes(StandardCharsets.UTF_8));
    }
}

package com.webhook.platform.api.controller;

import com.webhook.platform.api.service.HopByHopHeaders;
import com.webhook.platform.api.service.TunnelIngressService;
import com.webhook.platform.common.dto.tunnel.TunnelRequestMessage;
import com.webhook.platform.common.dto.tunnel.TunnelResponseMessage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Public endpoint for requests destined for a CLI tunnel. The request goes out over the WebSocket
 * the CLI holds open, and the CLI's answer comes back as this response.
 */
@RestController
@RequestMapping("/tunnel")
@Tag(name = "Tunnel Ingress", description = "Public tunnel ingress endpoints")
@RequiredArgsConstructor
public class TunnelIngressController {

    private final TunnelIngressService tunnelIngressService;

    @RequestMapping(value = "/{slug}", method = {RequestMethod.GET, RequestMethod.POST,
            RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE, RequestMethod.HEAD, RequestMethod.OPTIONS})
    @Operation(summary = "Tunnel ingress", description = "Forward request through CLI tunnel to local application")
    public ResponseEntity<String> handleTunnelRequest(
            @PathVariable("slug") String slug,
            @RequestBody(required = false) String body,
            HttpServletRequest request) {

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
            default -> HttpStatus.BAD_GATEWAY;
        };
    }

    private TunnelRequestMessage asTunnelRequest(String slug, String body, HttpServletRequest request) {
        return TunnelRequestMessage.builder()
                .type("TUNNEL_REQUEST")
                .requestId(UUID.randomUUID().toString())
                .method(request.getMethod())
                .path(pathAfterSlug(request.getRequestURI(), slug))
                .queryString(request.getQueryString())
                .headers(relayableHeaders(request))
                .body(body)
                .timestampMs(System.currentTimeMillis())
                .build();
    }

    /**
     * The part of the request URI that belongs to the developer's own service, with this
     * tunnel's prefix removed.
     *
     * <p>Plain string work, not {@code replaceFirst}. That method takes a <em>regex</em>, and
     * the slug arrives in the path of an endpoint anyone on the internet can call: a slug
     * carrying regex metacharacters either strips the wrong span (a {@code .} matches any
     * character), throws {@link java.util.regex.PatternSyntaxException} out of a request
     * handler, or hands a stranger the ability to choose a pattern that backtracks. None of
     * that is what the line was for - it only ever wanted "drop this prefix".
     */
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

    private ResponseEntity<String> relay(TunnelResponseMessage response) {
        HttpHeaders headers = new HttpHeaders();
        if (response.getHeaders() != null) {
            response.getHeaders().forEach((name, value) -> {
                if (!HopByHopHeaders.contains(name)) {
                    headers.add(name, value);
                }
            });
        }
        return ResponseEntity.status(response.getStatusCode()).headers(headers).body(response.getBody());
    }

    private ResponseEntity<String> problem(HttpStatus status, String error, String message) {
        return ResponseEntity.status(status)
                .body("{\"error\":\"" + error + "\",\"message\":\"" + message + "\"}");
    }
}

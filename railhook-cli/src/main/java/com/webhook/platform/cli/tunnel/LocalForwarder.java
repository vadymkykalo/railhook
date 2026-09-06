package com.webhook.platform.cli.tunnel;

import com.webhook.platform.common.dto.tunnel.TunnelRequestMessage;
import com.webhook.platform.common.dto.tunnel.TunnelResponseMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Forwards incoming tunnel requests to the local application
 * running on localhost:{port} and captures the response.
 */
public class LocalForwarder {

    private static final Logger log = LoggerFactory.getLogger(LocalForwarder.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final int localPort;
    private final HttpClient httpClient;

    public LocalForwarder(int localPort) {
        this.localPort = localPort;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * Forward the tunnel request to the local application and return the response.
     */
    public TunnelResponseMessage forward(TunnelRequestMessage request) {
        long startMs = System.currentTimeMillis();

        try {
            String path = request.getPath() != null ? request.getPath() : "/";
            if (!path.startsWith("/")) path = "/" + path;

            String url = "http://localhost:" + localPort + path;
            if (request.getQueryString() != null && !request.getQueryString().isEmpty()) {
                url += "?" + request.getQueryString();
            }

            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(TIMEOUT);

            // Set headers
            if (request.getHeaders() != null) {
                request.getHeaders().forEach((key, value) -> {
                    // Skip restricted headers
                    String lower = key.toLowerCase();
                    if (!lower.equals("host") && !lower.equals("content-length") &&
                        !lower.equals("connection") && !lower.equals("transfer-encoding")) {
                        try {
                            builder.header(key, value);
                        } catch (IllegalArgumentException e) {
                            log.trace("Skipping restricted header: {}", key);
                        }
                    }
                });
            }

            // Set method and body
            String method = request.getMethod() != null ? request.getMethod().toUpperCase() : "GET";
            switch (method) {
                case "GET" -> builder.GET();
                case "DELETE" -> builder.DELETE();
                case "POST" -> builder.POST(bodyPublisher(request.getBody()));
                case "PUT" -> builder.PUT(bodyPublisher(request.getBody()));
                case "PATCH" -> builder.method("PATCH", bodyPublisher(request.getBody()));
                case "HEAD" -> builder.method("HEAD", HttpRequest.BodyPublishers.noBody());
                case "OPTIONS" -> builder.method("OPTIONS", HttpRequest.BodyPublishers.noBody());
                default -> builder.method(method, bodyPublisher(request.getBody()));
            }

            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            long durationMs = System.currentTimeMillis() - startMs;

            // Extract response headers
            Map<String, String> responseHeaders = new LinkedHashMap<>();
            response.headers().map().forEach((key, values) -> {
                if (!values.isEmpty()) {
                    responseHeaders.put(key, values.get(0));
                }
            });

            log.info("{} {} → {} ({}ms)", method, path, response.statusCode(), durationMs);

            return TunnelResponseMessage.builder()
                    .type("TUNNEL_RESPONSE")
                    .requestId(request.getRequestId())
                    .statusCode(response.statusCode())
                    .headers(responseHeaders)
                    .body(response.body())
                    .durationMs(durationMs)
                    .timestampMs(System.currentTimeMillis())
                    .build();

        } catch (java.net.ConnectException e) {
            long durationMs = System.currentTimeMillis() - startMs;
            log.warn("{} {} → connection refused (localhost:{})", request.getMethod(), request.getPath(), localPort);
            return TunnelResponseMessage.builder()
                    .type("TUNNEL_RESPONSE")
                    .requestId(request.getRequestId())
                    .statusCode(502)
                    .error("Connection refused: localhost:" + localPort + " is not reachable")
                    .durationMs(durationMs)
                    .timestampMs(System.currentTimeMillis())
                    .build();

        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - startMs;
            log.error("{} {} → error: {}", request.getMethod(), request.getPath(), e.getMessage());
            return TunnelResponseMessage.builder()
                    .type("TUNNEL_RESPONSE")
                    .requestId(request.getRequestId())
                    .statusCode(502)
                    .error("Local forwarding error: " + e.getMessage())
                    .durationMs(durationMs)
                    .timestampMs(System.currentTimeMillis())
                    .build();
        }
    }

    private HttpRequest.BodyPublisher bodyPublisher(String body) {
        return body != null
                ? HttpRequest.BodyPublishers.ofString(body)
                : HttpRequest.BodyPublishers.noBody();
    }
}

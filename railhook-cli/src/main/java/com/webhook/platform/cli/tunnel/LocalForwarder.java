package com.webhook.platform.cli.tunnel;

import com.webhook.platform.common.dto.tunnel.TunnelBody;
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

            if (request.getHeaders() != null) {
                request.getHeaders().forEach((key, value) -> {
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

            // The provider's exact bytes: the local app checks the signature over them.
            byte[] body = request.bodyBytes();
            String method = request.getMethod() != null ? request.getMethod().toUpperCase() : "GET";
            switch (method) {
                case "GET" -> builder.GET();
                case "DELETE" -> builder.DELETE();
                case "POST" -> builder.POST(bodyPublisher(body));
                case "PUT" -> builder.PUT(bodyPublisher(body));
                case "PATCH" -> builder.method("PATCH", bodyPublisher(body));
                case "HEAD" -> builder.method("HEAD", HttpRequest.BodyPublishers.noBody());
                case "OPTIONS" -> builder.method("OPTIONS", HttpRequest.BodyPublishers.noBody());
                default -> builder.method(method, bodyPublisher(body));
            }

            HttpResponse<byte[]> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            long durationMs = System.currentTimeMillis() - startMs;

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
                    .rawBody(response.body(), TunnelBody.charsetOf(responseHeaders))
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

    private HttpRequest.BodyPublisher bodyPublisher(byte[] body) {
        return body != null
                ? HttpRequest.BodyPublishers.ofByteArray(body)
                : HttpRequest.BodyPublishers.noBody();
    }
}

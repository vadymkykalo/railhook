package com.webhook.platform.cli.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.cli.config.CliConfigService;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Separate from {@link HttpApiClient}, which refreshes and persists a tenant token. The operator
 * token is read from the environment or a flag each time and never written where
 * {@code railhook status} would print it. These commands are not in the web UI because the UI
 * shares the API's origin, and a token kept in a browser would turn any XSS into the master
 * credential.
 */
public class AdminApiClient {

    public static final String TOKEN_ENV = "RAILHOOK_ADMIN_TOKEN";
    private static final String TOKEN_HEADER = "X-Platform-Admin-Token";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String backendUrl;
    private final String token;

    public AdminApiClient(CliConfigService configService, String tokenOverride) {
        this.backendUrl = configService.load().getBackendUrl();
        String resolved = tokenOverride != null && !tokenOverride.isBlank()
                ? tokenOverride
                : System.getenv(TOKEN_ENV);
        if (resolved == null || resolved.isBlank()) {
            throw new IllegalStateException(
                    "No operator token. Pass --token, or set " + TOKEN_ENV + ".");
        }
        this.token = resolved;
    }

    public JsonNode get(String path) throws IOException, InterruptedException {
        return send("GET", path, null);
    }

    public JsonNode post(String path, String body) throws IOException, InterruptedException {
        return send("POST", path, body);
    }

    private JsonNode send(String method, String path, String body)
            throws IOException, InterruptedException {

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(backendUrl + path))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header(TOKEN_HEADER, token);

        if ("POST".equals(method)) {
            builder.POST(body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body));
        } else {
            builder.GET();
        }

        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 403 || response.statusCode() == 401) {
            throw new IOException("Refused (" + response.statusCode()
                    + "). The operator token is wrong, or this deployment has none configured.");
        }
        if (response.statusCode() == 404) {
            throw new IOException("No such organization.");
        }
        if (response.statusCode() >= 400) {
            throw new IOException("Request failed (" + response.statusCode() + "): " + response.body());
        }
        return response.body() == null || response.body().isBlank()
                ? objectMapper.createObjectNode()
                : objectMapper.readTree(response.body());
    }
}

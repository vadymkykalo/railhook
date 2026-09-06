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
 * Talks to {@code /api/v1/admin/**} with the deployment's operator credential.
 *
 * <p>Separate from {@link HttpApiClient} on purpose, and not a flag on it. That client carries a
 * tenant's bearer token, refreshes it, and persists it to {@code ~/.hookflow}; none of the three
 * is right for this credential. The operator token belongs to whoever runs the deployment, is
 * the same secret for every tenant on it, and is read from the environment or a flag each time —
 * so it is never written to a config file that a later {@code hookflow status} would print.
 *
 * <p>It is also why these commands live here and not in the dashboard. The web UI is served from
 * the same origin as the API; a platform-admin token kept in a browser would turn any XSS
 * anywhere in the tenant dashboard into the deployment's master credential, which is a much
 * worse trade than typing a token into a terminal.
 */
public class AdminApiClient {

    public static final String TOKEN_ENV = "HOOKFLOW_ADMIN_TOKEN";
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

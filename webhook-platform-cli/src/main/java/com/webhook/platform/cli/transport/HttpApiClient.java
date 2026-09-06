package com.webhook.platform.cli.transport;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.webhook.platform.cli.config.CliConfig;
import com.webhook.platform.cli.config.CliConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * HTTP client for communicating with the Railhook backend API.
 * Handles authentication headers, token refresh, and JSON serialization.
 */
public class HttpApiClient {

    private static final Logger log = LoggerFactory.getLogger(HttpApiClient.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final CliConfigService configService;

    public HttpApiClient(CliConfigService configService) {
        this.configService = configService;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    public ObjectMapper objectMapper() {
        return objectMapper;
    }

    public <T> T get(String path, Class<T> responseType) throws IOException, InterruptedException {
        HttpResponse<String> response = doRequest("GET", path, null);
        handleErrorResponse(response);
        return objectMapper.readValue(response.body(), responseType);
    }

    public <T> T get(String path, TypeReference<T> responseType) throws IOException, InterruptedException {
        HttpResponse<String> response = doRequest("GET", path, null);
        handleErrorResponse(response);
        return objectMapper.readValue(response.body(), responseType);
    }

    public <T> T post(String path, Object body, Class<T> responseType) throws IOException, InterruptedException {
        String json = body != null ? objectMapper.writeValueAsString(body) : null;
        HttpResponse<String> response = doRequest("POST", path, json);
        handleErrorResponse(response);
        if (responseType == Void.class) return null;
        return objectMapper.readValue(response.body(), responseType);
    }

    public JsonNode postForJson(String path, Object body) throws IOException, InterruptedException {
        String json = body != null ? objectMapper.writeValueAsString(body) : null;
        HttpResponse<String> response = doRequest("POST", path, json);
        handleErrorResponse(response);
        return objectMapper.readTree(response.body());
    }

    public HttpResponse<String> postRaw(String path, Object body) throws IOException, InterruptedException {
        String json = body != null ? objectMapper.writeValueAsString(body) : null;
        return doRequest("POST", path, json);
    }

    public <T> T delete(String path, Class<T> responseType) throws IOException, InterruptedException {
        HttpResponse<String> response = doRequest("DELETE", path, null);
        handleErrorResponse(response);
        if (responseType == Void.class) return null;
        return objectMapper.readValue(response.body(), responseType);
    }

    public JsonNode getHealth() throws IOException, InterruptedException {
        CliConfig config = configService.load();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.getBackendUrl() + "/actuator/health"))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return objectMapper.readTree(response.body());
    }

    private HttpResponse<String> doRequest(String method, String path, String body)
            throws IOException, InterruptedException {

        CliConfig config = configService.load();
        String url = config.getBackendUrl() + path;

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json");

        if (config.getAccessToken() != null) {
            builder.header("Authorization", "Bearer " + config.getAccessToken());
        }

        switch (method) {
            case "GET" -> builder.GET();
            case "DELETE" -> builder.DELETE();
            case "POST" -> builder.POST(body != null
                    ? HttpRequest.BodyPublishers.ofString(body)
                    : HttpRequest.BodyPublishers.noBody());
            case "PUT" -> builder.PUT(body != null
                    ? HttpRequest.BodyPublishers.ofString(body)
                    : HttpRequest.BodyPublishers.noBody());
            default -> throw new IllegalArgumentException("Unsupported method: " + method);
        }

        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());

        // Auto-refresh token on 401
        if (response.statusCode() == 401 && config.getRefreshToken() != null) {
            if (tryRefreshToken(config)) {
                // Retry with new token
                config = configService.load();
                builder.header("Authorization", "Bearer " + config.getAccessToken());
                response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            }
        }

        return response;
    }

    private boolean tryRefreshToken(CliConfig config) {
        try {
            String refreshUrl = config.getBackendUrl() + "/api/v1/auth/refresh";
            String body = objectMapper.writeValueAsString(Map.of("refreshToken", config.getRefreshToken()));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(refreshUrl))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonNode json = objectMapper.readTree(response.body());
                config.setAccessToken(json.get("accessToken").asText());
                if (json.has("refreshToken") && !json.get("refreshToken").isNull()) {
                    config.setRefreshToken(json.get("refreshToken").asText());
                }
                configService.save(config);
                log.debug("Token refreshed successfully");
                return true;
            }
        } catch (Exception e) {
            log.debug("Token refresh failed: {}", e.getMessage());
        }
        return false;
    }

    private void handleErrorResponse(HttpResponse<String> response) throws IOException {
        if (response.statusCode() >= 400) {
            String message;
            try {
                JsonNode json = objectMapper.readTree(response.body());
                message = json.has("message") ? json.get("message").asText() : response.body();
            } catch (Exception e) {
                message = response.body();
            }
            throw new ApiException(response.statusCode(), message);
        }
    }

    public static class ApiException extends IOException {
        private final int statusCode;

        public ApiException(int statusCode, String message) {
            super("HTTP " + statusCode + ": " + message);
            this.statusCode = statusCode;
        }

        public int getStatusCode() { return statusCode; }
    }
}

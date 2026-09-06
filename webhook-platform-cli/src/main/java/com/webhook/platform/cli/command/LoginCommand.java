package com.webhook.platform.cli.command;

import com.fasterxml.jackson.databind.JsonNode;
import com.webhook.platform.cli.config.CliConfig;
import com.webhook.platform.cli.config.CliConfigService;
import com.webhook.platform.cli.transport.HttpApiClient;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.PrintStream;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(
        name = "login",
        description = "Authenticate with the Railhook platform",
        mixinStandardHelpOptions = true
)
public class LoginCommand implements Callable<Integer> {

    @Option(names = {"-s", "--server"}, description = "Backend server URL (default: http://localhost:8080)")
    private String server;

    @Option(names = {"--email"}, description = "Email for direct login (non-interactive)")
    private String email;

    @Option(names = {"--password"}, description = "Password for direct login (non-interactive)", interactive = true)
    private String password;

    @Option(names = {"--device"}, description = "Use device code flow (default if no email/password)")
    private boolean deviceFlow;

    private final PrintStream out = System.out;
    private final PrintStream err = System.err;

    @Override
    public Integer call() throws Exception {
        CliConfigService configService = new CliConfigService();
        CliConfig config = configService.load();

        if (server != null) {
            config.setBackendUrl(server);
            configService.save(config);
        }

        HttpApiClient client = new HttpApiClient(configService);

        if (email != null && password != null) {
            return directLogin(client, configService, config);
        } else {
            return deviceCodeLogin(client, configService, config);
        }
    }

    private int directLogin(HttpApiClient client, CliConfigService configService, CliConfig config)
            throws Exception {
        out.println("Logging in with email...");

        JsonNode response = client.postForJson("/api/v1/auth/login", Map.of(
                "email", email,
                "password", password
        ));

        config.setAccessToken(response.get("accessToken").asText());
        if (response.has("refreshToken") && !response.get("refreshToken").isNull()) {
            config.setRefreshToken(response.get("refreshToken").asText());
        }

        // Fetch current user info
        fetchAndStoreUserInfo(client, configService, config);

        out.println("✓ Logged in successfully");
        out.println("  Config saved to: " + configService.getConfigPath());
        return 0;
    }

    private int deviceCodeLogin(HttpApiClient client, CliConfigService configService, CliConfig config)
            throws Exception {
        out.println("Initiating device authorization...");
        out.println();

        // Step 1: Get device code
        JsonNode deviceResponse = client.postForJson("/api/v1/auth/device/code", null);
        String deviceCode = deviceResponse.get("deviceCode").asText();
        String userCode = deviceResponse.get("userCode").asText();
        String verificationUrl = deviceResponse.get("verificationUrl").asText();
        int pollInterval = deviceResponse.has("pollIntervalSeconds")
                ? deviceResponse.get("pollIntervalSeconds").asInt() : 5;
        int expiresIn = deviceResponse.has("expiresInSeconds")
                ? deviceResponse.get("expiresInSeconds").asInt() : 600;

        out.println("  Open this URL in your browser:");
        out.println();
        out.println("    " + verificationUrl);
        out.println();
        out.println("  And enter code: " + userCode);
        out.println();
        out.println("  Waiting for authorization (expires in " + (expiresIn / 60) + " minutes)...");

        // Step 2: Poll for token
        long deadline = System.currentTimeMillis() + (expiresIn * 1000L);
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(pollInterval * 1000L);

            HttpResponse<String> pollResponse = client.postRaw("/api/v1/auth/device/token",
                    Map.of("deviceCode", deviceCode));

            if (pollResponse.statusCode() == 200) {
                JsonNode tokenResponse = client.objectMapper().readTree(pollResponse.body());
                config.setAccessToken(tokenResponse.get("accessToken").asText());
                if (tokenResponse.has("refreshToken") && !tokenResponse.get("refreshToken").isNull()) {
                    config.setRefreshToken(tokenResponse.get("refreshToken").asText());
                }

                fetchAndStoreUserInfo(client, configService, config);

                out.println();
                out.println("✓ Logged in successfully");
                out.println("  Config saved to: " + configService.getConfigPath());
                return 0;
            } else if (pollResponse.statusCode() == 202) {
                // Still pending — continue polling
                out.print(".");
                out.flush();
            } else if (pollResponse.statusCode() == 403) {
                err.println("\n✗ Authorization denied");
                return 1;
            } else if (pollResponse.statusCode() == 410) {
                err.println("\n✗ Device code expired");
                return 1;
            } else {
                // Unexpected — continue polling
                out.print("?");
                out.flush();
            }
        }

        err.println("\n✗ Timed out waiting for authorization");
        return 1;
    }

    private void fetchAndStoreUserInfo(HttpApiClient client, CliConfigService configService, CliConfig config)
            throws Exception {
        try {
            configService.save(config); // save token first for the GET request
            JsonNode me = client.get("/api/v1/auth/me", JsonNode.class);
            if (me.has("user") && me.get("user").has("id")) {
                config.setUserId(me.get("user").get("id").asText());
            }
            if (me.has("organization") && me.get("organization").has("id")) {
                config.setOrganizationId(me.get("organization").get("id").asText());
            }
        } catch (Exception e) {
            // Non-critical — we still have the token
        }
        configService.save(config);
    }
}

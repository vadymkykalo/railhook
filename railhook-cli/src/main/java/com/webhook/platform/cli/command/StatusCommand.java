package com.webhook.platform.cli.command;

import com.fasterxml.jackson.databind.JsonNode;
import com.webhook.platform.cli.config.CliConfig;
import com.webhook.platform.cli.config.CliConfigService;
import com.webhook.platform.cli.transport.HttpApiClient;
import picocli.CommandLine.Command;

import java.io.PrintStream;
import java.util.concurrent.Callable;

@Command(
        name = "status",
        description = "Show current CLI status, auth state, active tunnels, and backend health",
        mixinStandardHelpOptions = true
)
public class StatusCommand implements Callable<Integer> {

    private final PrintStream out = System.out;
    private final PrintStream err = System.err;

    @Override
    public Integer call() throws Exception {
        CliConfigService configService = new CliConfigService();
        CliConfig config = configService.load();

        out.println("Railhook CLI Status");
        out.println("═══════════════════════════════════════");
        out.println();

        // Config
        out.println("  Config:     " + configService.getConfigPath());
        out.println("  Backend:    " + config.getBackendUrl());

        // Auth
        if (config.isAuthenticated()) {
            out.println("  Auth:       ✓ authenticated");
            if (config.getUserId() != null) out.println("  User ID:    " + config.getUserId());
            if (config.getOrganizationId() != null) out.println("  Org ID:     " + config.getOrganizationId());
            if (config.getActiveProjectId() != null) out.println("  Project ID: " + config.getActiveProjectId());
        } else {
            out.println("  Auth:       ✗ not authenticated");
            out.println();
            out.println("  Run 'railhook login' to authenticate.");
            return 0;
        }

        out.println();
        HttpApiClient client = new HttpApiClient(configService);

        // Backend health
        out.print("  Health:     ");
        try {
            JsonNode health = client.getHealth();
            String status = health.has("status") ? health.get("status").asText() : "unknown";
            if ("UP".equalsIgnoreCase(status)) {
                out.println("✓ " + status);
            } else {
                out.println("⚠ " + status);
            }
        } catch (Exception e) {
            out.println("✗ unreachable (" + e.getMessage() + ")");
        }

        // Active tunnels
        try {
            JsonNode tunnelStatus = client.get("/api/v1/tunnels/status", JsonNode.class);
            int activeTunnels = tunnelStatus.has("activeTunnels") ? tunnelStatus.get("activeTunnels").asInt() : 0;
            int pendingRequests = tunnelStatus.has("pendingRequests") ? tunnelStatus.get("pendingRequests").asInt() : 0;

            out.println();
            out.println("  Tunnels:");
            out.println("    Active:   " + activeTunnels);
            out.println("    Pending:  " + pendingRequests);

            if (tunnelStatus.has("myTunnels") && tunnelStatus.get("myTunnels").isArray()) {
                JsonNode myTunnels = tunnelStatus.get("myTunnels");
                if (myTunnels.size() > 0) {
                    out.println();
                    out.println("  My Tunnels:");
                    for (JsonNode tunnel : myTunnels) {
                        out.printf("    • %s → localhost:%d (%s)%n",
                                tunnel.has("publicUrl") ? tunnel.get("publicUrl").asText() : tunnel.get("publicSlug").asText(),
                                tunnel.has("localPort") ? tunnel.get("localPort").asInt() : 0,
                                tunnel.has("status") ? tunnel.get("status").asText() : "unknown");
                    }
                }
            }
        } catch (Exception e) {
            out.println("  Tunnels:    ✗ could not fetch (" + e.getMessage() + ")");
        }

        out.println();
        return 0;
    }
}

package com.webhook.platform.cli.command;

import com.fasterxml.jackson.databind.JsonNode;
import com.webhook.platform.cli.config.CliConfig;
import com.webhook.platform.cli.config.CliConfigService;
import com.webhook.platform.cli.transport.HttpApiClient;
import picocli.CommandLine.Command;

import java.io.PrintStream;
import java.util.concurrent.Callable;

@Command(
        name = "tunnels",
        description = "List active tunnel sessions",
        mixinStandardHelpOptions = true,
        subcommands = { TunnelsCommand.ListSubcommand.class, TunnelsCommand.CloseSubcommand.class, TunnelsCommand.StatusSubcommand.class }
)
public class TunnelsCommand implements Callable<Integer> {

    private final PrintStream out = System.out;

    @Override
    public Integer call() throws Exception {
        return new ListSubcommand().call();
    }

    @Command(name = "list", description = "List active tunnel sessions", mixinStandardHelpOptions = true)
    public static class ListSubcommand implements Callable<Integer> {

        private final PrintStream out = System.out;
        private final PrintStream err = System.err;

        @Override
        public Integer call() throws Exception {
            CliConfigService configService = new CliConfigService();
            CliConfig config = configService.load();

            if (!config.isAuthenticated()) {
                err.println("✗ Not authenticated. Run 'railhook login' first.");
                return 1;
            }

            HttpApiClient client = new HttpApiClient(configService);
            JsonNode tunnels = client.get("/api/v1/tunnels", JsonNode.class);

            if (!tunnels.isArray() || tunnels.size() == 0) {
                out.println("No active tunnels.");
                return 0;
            }

            out.println("Active Tunnels:");
            out.println("─────────────────────────────────────────────────────────");
            out.printf("  %-38s  %-6s  %-30s  %s%n", "ID", "PORT", "PUBLIC URL", "STATUS");
            out.println("─────────────────────────────────────────────────────────");

            for (JsonNode tunnel : tunnels) {
                out.printf("  %-38s  %-6d  %-30s  %s%n",
                        tunnel.has("id") ? tunnel.get("id").asText() : "?",
                        tunnel.has("localPort") ? tunnel.get("localPort").asInt() : 0,
                        tunnel.has("publicUrl") ? tunnel.get("publicUrl").asText() : "?",
                        tunnel.has("status") ? tunnel.get("status").asText() : "?");
            }

            out.println();
            out.println("Total: " + tunnels.size());
            return 0;
        }
    }

    @Command(name = "close", description = "Close a tunnel session", mixinStandardHelpOptions = true)
    public static class CloseSubcommand implements Callable<Integer> {

        @picocli.CommandLine.Parameters(index = "0", description = "Tunnel session ID to close")
        private String sessionId;

        private final PrintStream out = System.out;
        private final PrintStream err = System.err;

        @Override
        public Integer call() throws Exception {
            CliConfigService configService = new CliConfigService();
            CliConfig config = configService.load();

            if (!config.isAuthenticated()) {
                err.println("✗ Not authenticated. Run 'railhook login' first.");
                return 1;
            }

            HttpApiClient client = new HttpApiClient(configService);
            client.delete("/api/v1/tunnels/" + sessionId, Void.class);
            out.println("✓ Tunnel " + sessionId + " closed");
            return 0;
        }
    }

    @Command(name = "status", description = "Show tunnel registry stats and bandwidth usage", mixinStandardHelpOptions = true)
    public static class StatusSubcommand implements Callable<Integer> {

        private final PrintStream out = System.out;
        private final PrintStream err = System.err;

        @Override
        public Integer call() throws Exception {
            CliConfigService configService = new CliConfigService();
            CliConfig config = configService.load();

            if (!config.isAuthenticated()) {
                err.println("✗ Not authenticated. Run 'railhook login' first.");
                return 1;
            }

            HttpApiClient client = new HttpApiClient(configService);
            JsonNode status = client.get("/api/v1/tunnels/status", JsonNode.class);

            out.println("Tunnel Status");
            out.println("══════════════════════════════════════");
            out.println("  Active tunnels:    " + status.path("activeTunnels").asInt(0));
            out.println("  Pending requests:  " + status.path("pendingRequests").asInt(0));

            long bw = status.path("bandwidthBytesThisMonth").asLong(0);
            out.println("  Bandwidth (month): " + formatBytes(bw));
            out.println();

            JsonNode myTunnels = status.path("myTunnels");
            if (myTunnels.isArray() && myTunnels.size() > 0) {
                out.println("  My Tunnels:");
                for (JsonNode t : myTunnels) {
                    out.printf("    • %s → :%d  %s%n",
                            t.path("publicUrl").asText("?"),
                            t.path("localPort").asInt(0),
                            t.path("status").asText("?"));
                }
            } else {
                out.println("  No active tunnels for your user.");
            }

            return 0;
        }

        private String formatBytes(long bytes) {
            if (bytes < 1024) return bytes + " B";
            if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
            if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
            return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
        }
    }
}

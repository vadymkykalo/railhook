package com.webhook.platform.cli.command;

import com.fasterxml.jackson.databind.JsonNode;
import com.webhook.platform.cli.config.CliConfig;
import com.webhook.platform.cli.config.CliConfigService;
import com.webhook.platform.cli.transport.HttpApiClient;
import com.webhook.platform.cli.transport.WebSocketTunnelClient;
import com.webhook.platform.cli.tunnel.LocalForwarder;
import java.util.concurrent.CompletableFuture;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.PrintStream;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;

@Command(
        name = "listen",
        description = "Start a local webhook tunnel forwarding to localhost:<port>",
        mixinStandardHelpOptions = true
)
public class ListenCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Local port to forward requests to")
    private int port;

    @Option(names = {"-p", "--project"}, description = "Project ID to associate with the tunnel")
    private String projectId;

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

        if (port < 1 || port > 65535) {
            err.println("✗ Invalid port: " + port);
            return 1;
        }

        HttpApiClient client = new HttpApiClient(configService);
        LocalForwarder forwarder = new LocalForwarder(port);

        out.println("Creating tunnel session...");

        // Step 1: Create tunnel session via REST API
        String query = "/api/v1/tunnels?localPort=" + port;
        if (projectId != null) query += "&projectId=" + projectId;
        query += "&clientInfo=railhook-cli/1.0.0";

        JsonNode createResponse = client.postForJson(query, null);
        String tunnelToken = createResponse.get("tunnelToken").asText();
        String publicUrl = createResponse.get("publicUrl").asText();
        String tunnelId = createResponse.get("id").asText();

        out.println("Tunnel created: " + tunnelId);

        // Step 2: Connect WebSocket
        String wsUrl = config.getWsUrl();
        WebSocketTunnelClient wsClient = new WebSocketTunnelClient(wsUrl, tunnelToken, port);

        CountDownLatch shutdownLatch = new CountDownLatch(1);

        wsClient.onRegistered(url -> {
            out.println();
            out.println("╔══════════════════════════════════════════════════════╗");
            out.println("║  Railhook tunnel is active                          ║");
            out.println("╚══════════════════════════════════════════════════════╝");
            out.println();
            out.println("  Public URL:  " + url);
            out.println("  Forwarding:  → http://localhost:" + port);
            out.println("  Tunnel ID:   " + tunnelId);
            out.println();
            out.println("  Press Ctrl+C to stop");
            out.println();
            out.println("  Requests:");
            out.println("  ─────────────────────────────────────────────────────");
        });

        wsClient.onRequest(request -> {
            // Forward to local app in a separate thread
            CompletableFuture.runAsync(() -> {
                var response = forwarder.forward(request);
                wsClient.sendResponse(response);
            });
        });

        wsClient.onDisconnected(() -> {
            if (!wsClient.isConnected()) {
                out.println("  [disconnected]");
            }
        });

        wsClient.onReconnecting(msg -> out.println("  [" + msg + "]"));

        // Shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            out.println();
            out.println("Shutting down tunnel...");
            wsClient.disconnect();
            try {
                client.delete("/api/v1/tunnels/" + tunnelId, Void.class);
            } catch (Exception e) {
                // Best effort cleanup
            }
            shutdownLatch.countDown();
        }));

        wsClient.connect();

        // Block until shutdown
        shutdownLatch.await();
        out.println("Tunnel closed.");
        return 0;
    }
}

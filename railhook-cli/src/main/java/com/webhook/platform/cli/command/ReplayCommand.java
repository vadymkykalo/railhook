package com.webhook.platform.cli.command;

import com.fasterxml.jackson.databind.JsonNode;
import com.webhook.platform.cli.config.CliConfig;
import com.webhook.platform.cli.config.CliConfigService;
import com.webhook.platform.cli.transport.HttpApiClient;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.PrintStream;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(
        name = "replay",
        description = "Replay events for a project",
        mixinStandardHelpOptions = true
)
public class ReplayCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Project ID to replay events for")
    private String projectId;

    @Option(names = {"--event-type"}, description = "Filter by event type")
    private String eventType;

    @Option(names = {"--from"}, description = "Start time (ISO-8601, default: 24h ago)")
    private String from;

    @Option(names = {"--to"}, description = "End time (ISO-8601, default: now)")
    private String to;

    @Option(names = {"--endpoint"}, description = "Filter by endpoint ID")
    private String endpointId;

    @Option(names = {"--dry-run"}, description = "Estimate only, do not replay")
    private boolean dryRun;

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

        Instant fromDate = from != null ? Instant.parse(from) : Instant.now().minus(24, ChronoUnit.HOURS);
        Instant toDate = to != null ? Instant.parse(to) : Instant.now();

        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("fromDate", fromDate.toString());
        body.put("toDate", toDate.toString());
        if (eventType != null) body.put("eventType", eventType);
        if (endpointId != null) body.put("endpointId", endpointId);

        String basePath = "/api/v1/projects/" + projectId + "/replay";

        if (dryRun) {
            out.println("Estimating replay...");
            JsonNode estimate = client.postForJson(basePath + "/estimate", body);
            out.println();
            out.println("Replay Estimate:");
            out.println("  Events matched:     " + getField(estimate, "matchingEvents"));
            out.println("  Deliveries created: " + getField(estimate, "estimatedDeliveries"));
            out.println("  Time range:         " + fromDate + " → " + toDate);
            if (eventType != null) out.println("  Event type:         " + eventType);
            if (endpointId != null) out.println("  Endpoint:           " + endpointId);
        } else {
            out.println("Creating replay session...");
            JsonNode session = client.postForJson(basePath, body);
            String sessionId = getField(session, "id");
            String status = getField(session, "status");

            out.println();
            out.println("✓ Replay session created");
            out.println("  Session ID:  " + sessionId);
            out.println("  Status:      " + status);
            out.println("  Time range:  " + fromDate + " → " + toDate);
            if (eventType != null) out.println("  Event type:  " + eventType);

            // Poll for completion
            out.println();
            out.println("  Waiting for completion...");
            for (int i = 0; i < 60; i++) {
                Thread.sleep(2000);
                JsonNode progress = client.get(basePath + "/" + sessionId, JsonNode.class);
                String currentStatus = getField(progress, "status");

                if ("COMPLETED".equals(currentStatus) || "FAILED".equals(currentStatus) ||
                    "CANCELLED".equals(currentStatus)) {
                    out.println();
                    out.println("  Final status:      " + currentStatus);
                    out.println("  Events processed:  " + getField(progress, "processedEvents"));
                    out.println("  Deliveries:        " + getField(progress, "deliveriesCreated"));
                    return "COMPLETED".equals(currentStatus) ? 0 : 1;
                }

                out.print(".");
                out.flush();
            }
            out.println();
            out.println("  Session still running. Check status with:");
            out.println("  railhook replay " + projectId + " --session " + sessionId);
        }

        return 0;
    }

    private String getField(JsonNode node, String field) {
        return node.has(field) ? node.get(field).asText() : "N/A";
    }
}

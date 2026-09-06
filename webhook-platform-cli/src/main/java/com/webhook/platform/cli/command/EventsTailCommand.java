package com.webhook.platform.cli.command;

import com.fasterxml.jackson.databind.JsonNode;
import com.webhook.platform.cli.config.CliConfig;
import com.webhook.platform.cli.config.CliConfigService;
import com.webhook.platform.cli.transport.HttpApiClient;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Option;

import java.io.PrintStream;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Callable;

@Command(
        name = "events",
        description = "Tail recent events for a project",
        mixinStandardHelpOptions = true
)
public class EventsTailCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Project ID")
    private String projectId;

    @Option(names = {"-n", "--count"}, description = "Number of events to show (default: 20)", defaultValue = "20")
    private int count;

    @Option(names = {"-f", "--follow"}, description = "Follow mode — poll for new events")
    private boolean follow;

    @Option(names = {"--type"}, description = "Filter by event type")
    private String eventType;

    private final PrintStream out = System.out;
    private final PrintStream err = System.err;

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    @Override
    public Integer call() throws Exception {
        CliConfigService configService = new CliConfigService();
        CliConfig config = configService.load();

        if (!config.isAuthenticated()) {
            err.println("✗ Not authenticated. Run 'railhook login' first.");
            return 1;
        }

        HttpApiClient client = new HttpApiClient(configService);

        String path = "/api/v1/projects/" + projectId + "/events?size=" + count + "&sort=createdAt,desc";
        if (eventType != null) path += "&eventType=" + eventType;

        out.println("Recent events for project " + projectId + ":");
        out.println("─────────────────────────────────────────────────────────");

        JsonNode response = client.get(path, JsonNode.class);
        JsonNode events = response.has("content") ? response.get("content") : response;

        if (events.isArray()) {
            for (int i = events.size() - 1; i >= 0; i--) {
                printEvent(events.get(i));
            }
        }

        if (follow) {
            out.println();
            out.println("Following new events (Ctrl+C to stop)...");
            String lastId = null;
            if (events.isArray() && events.size() > 0) {
                lastId = events.get(0).has("id") ? events.get(0).get("id").asText() : null;
            }

            while (true) {
                Thread.sleep(3000);
                try {
                    String pollPath = "/api/v1/projects/" + projectId + "/events?size=10&sort=createdAt,desc";
                    if (eventType != null) pollPath += "&eventType=" + eventType;

                    JsonNode pollResponse = client.get(pollPath, JsonNode.class);
                    JsonNode newEvents = pollResponse.has("content") ? pollResponse.get("content") : pollResponse;

                    if (newEvents.isArray()) {
                        for (int i = newEvents.size() - 1; i >= 0; i--) {
                            JsonNode event = newEvents.get(i);
                            String eventId = event.has("id") ? event.get("id").asText() : "";
                            if (lastId != null && eventId.equals(lastId)) break;
                            if (lastId == null || eventId.compareTo(lastId) > 0) {
                                printEvent(event);
                            }
                        }
                        if (newEvents.size() > 0) {
                            lastId = newEvents.get(0).has("id") ? newEvents.get(0).get("id").asText() : lastId;
                        }
                    }
                } catch (Exception e) {
                    // Silently retry
                }
            }
        }

        return 0;
    }

    private void printEvent(JsonNode event) {
        String id = event.has("id") ? event.get("id").asText().substring(0, 8) : "?";
        String type = event.has("eventType") ? event.get("eventType").asText() : "?";
        String time = "?";
        if (event.has("createdAt")) {
            try {
                time = TIME_FMT.format(Instant.parse(event.get("createdAt").asText()));
            } catch (Exception e) {
                time = event.get("createdAt").asText();
            }
        }
        int deliveries = event.has("deliveriesCreated") ? event.get("deliveriesCreated").asInt() : 0;

        out.printf("  %s  %-8s  %-30s  deliveries: %d%n", time, id + "…", type, deliveries);
    }
}

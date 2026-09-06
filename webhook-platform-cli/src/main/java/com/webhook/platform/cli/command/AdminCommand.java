package com.webhook.platform.cli.command;

import com.fasterxml.jackson.databind.JsonNode;
import com.webhook.platform.cli.config.CliConfigService;
import com.webhook.platform.cli.transport.AdminApiClient;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.PrintStream;
import java.util.concurrent.Callable;

/**
 * The operator back-office, from a terminal.
 *
 * <p>Everything here needs the deployment's platform-admin token, which is the same secret for
 * every tenant on the instance. It is read from {@code HOOKFLOW_ADMIN_TOKEN} or {@code --token}
 * and never saved: a credential that outlives the command that used it is one more place it can
 * leak from, and this one is not a login.
 *
 * <p>Answering "who is on this instance", "are they near their limit" and "make this one stop"
 * used to mean a psql session against a customer's tables — a bad place to answer a support
 * question and a worse place to act on an abuse report.
 */
@Command(
        name = "admin",
        description = "Operator commands: list tenants, inspect usage, suspend and reinstate",
        mixinStandardHelpOptions = true,
        subcommands = {
                AdminCommand.OrgsCommand.class,
                AdminCommand.OrgCommand.class,
                AdminCommand.SuspendCommand.class,
                AdminCommand.ReinstateCommand.class,
        }
)
public class AdminCommand implements Runnable {

    @Override
    public void run() {
        System.out.println("Run 'hookflow admin --help' to see the operator commands.");
    }

    /** Shared by every subcommand: where the token comes from, and how failures are reported. */
    abstract static class AdminSubcommand implements Callable<Integer> {

        @Option(names = {"-t", "--token"},
                description = "Platform-admin token. Defaults to $" + AdminApiClient.TOKEN_ENV + ".")
        String token;

        final PrintStream out = System.out;
        final PrintStream err = System.err;

        @Override
        public Integer call() {
            try {
                return run(new AdminApiClient(new CliConfigService(), token));
            } catch (IllegalStateException e) {
                err.println(e.getMessage());
                return 2;
            } catch (Exception e) {
                err.println("✗ " + e.getMessage());
                return 1;
            }
        }

        abstract Integer run(AdminApiClient client) throws Exception;

        static String text(JsonNode node, String field) {
            JsonNode value = node.get(field);
            return value == null || value.isNull() ? "—" : value.asText();
        }
    }

    @Command(name = "orgs", description = "List the organizations on this deployment",
            mixinStandardHelpOptions = true)
    public static class OrgsCommand extends AdminSubcommand {

        @Option(names = {"-s", "--search"}, description = "Narrow by organization name")
        String search;

        @Option(names = "--suspended", description = "Only organizations currently suspended")
        boolean suspendedOnly;

        @Option(names = "--size", description = "How many to show (default 50)")
        int size = 50;

        @Override
        Integer run(AdminApiClient client) throws Exception {
            StringBuilder path = new StringBuilder("/api/v1/admin/organizations?size=" + size);
            if (search != null && !search.isBlank()) {
                path.append("&search=").append(java.net.URLEncoder.encode(search, java.nio.charset.StandardCharsets.UTF_8));
            }
            if (suspendedOnly) {
                path.append("&suspendedOnly=true");
            }

            JsonNode page = client.get(path.toString());
            JsonNode content = page.path("content");
            if (!content.isArray() || content.isEmpty()) {
                out.println("No organizations" + (suspendedOnly ? " are suspended." : " on this deployment."));
                return 0;
            }

            out.printf("%-38s %-28s %-12s %8s %8s  %s%n",
                    "ID", "NAME", "PLAN", "PROJECTS", "MEMBERS", "STATE");
            for (JsonNode org : content) {
                boolean suspended = org.hasNonNull("suspendedAt");
                out.printf("%-38s %-28s %-12s %8d %8d  %s%n",
                        text(org, "id"),
                        truncate(text(org, "name"), 28),
                        truncate(text(org, "planName"), 12),
                        org.path("projectCount").asLong(),
                        org.path("memberCount").asLong(),
                        suspended ? "SUSPENDED — " + text(org, "suspensionReason") : text(org, "billingStatus"));
            }
            long total = page.path("totalElements").asLong();
            if (total > content.size()) {
                out.printf("%n%d of %d shown. Use --size or --search to narrow.%n", content.size(), total);
            }
            return 0;
        }

        private static String truncate(String value, int width) {
            return value.length() <= width ? value : value.substring(0, width - 1) + "…";
        }
    }

    @Command(name = "org", description = "Show one organization, with what it has used",
            mixinStandardHelpOptions = true)
    public static class OrgCommand extends AdminSubcommand {

        @Parameters(index = "0", paramLabel = "<organizationId>", description = "The organization to inspect")
        String organizationId;

        @Override
        Integer run(AdminApiClient client) throws Exception {
            JsonNode org = client.get("/api/v1/admin/organizations/" + organizationId);

            out.println(text(org, "name"));
            out.println("═══════════════════════════════════════");
            out.println("  ID:        " + text(org, "id"));
            out.println("  Plan:      " + text(org, "planName"));
            out.println("  Billing:   " + text(org, "billingStatus"));
            out.println("  Created:   " + text(org, "createdAt"));
            out.println("  Projects:  " + org.path("projectCount").asLong());
            out.println("  Members:   " + org.path("memberCount").asLong());

            if (org.hasNonNull("suspendedAt")) {
                out.println();
                out.println("  ⚠ SUSPENDED " + text(org, "suspendedAt"));
                out.println("    Reason:  " + text(org, "suspensionReason"));
                out.println("    By:      " + text(org, "suspendedBy"));
            }

            // Best-effort: an organization is still worth showing when its usage cannot be read.
            try {
                JsonNode usage = client.get("/api/v1/admin/organizations/" + organizationId + "/usage");
                out.println();
                out.println("  Usage this period (" + text(usage, "periodStart") + " → " + text(usage, "periodEnd") + ")");
                printResource("Events", usage.path("events"));
                printResource("Endpoints", usage.path("endpoints"));
                printResource("Projects", usage.path("projects"));
                printResource("Members", usage.path("members"));
            } catch (Exception e) {
                out.println();
                out.println("  Usage:     unavailable (" + e.getMessage() + ")");
            }
            return 0;
        }

        private void printResource(String label, JsonNode resource) {
            if (resource.isMissingNode() || resource.isNull()) return;
            long limit = resource.path("limit").asLong();
            // -1 is how the plan catalog spells "unlimited"; printed as a number it reads as a
            // limit of minus one, which is what the dashboard had to be taught not to do.
            String against = limit < 0 ? "unlimited" : String.valueOf(limit);
            out.printf("    %-10s %d / %s%s%n", label + ":",
                    resource.path("current").asLong(), against,
                    limit > 0 ? String.format("  (%.1f%%)", resource.path("percentUsed").asDouble()) : "");
        }
    }

    @Command(name = "suspend", description = "Stop an organization changing anything, ingest included",
            mixinStandardHelpOptions = true)
    public static class SuspendCommand extends AdminSubcommand {

        @Parameters(index = "0", paramLabel = "<organizationId>", description = "The organization to suspend")
        String organizationId;

        @Option(names = {"-r", "--reason"}, required = true,
                description = "Why. The tenant is shown this, so write it for them.")
        String reason;

        @Option(names = "--by", description = "Who is doing it, for the audit trail")
        String by;

        @Override
        Integer run(AdminApiClient client) throws Exception {
            String body = "{\"reason\":" + quote(reason) + (by == null ? "" : ",\"suspendedBy\":" + quote(by)) + "}";
            JsonNode org = client.post("/api/v1/admin/organizations/" + organizationId + "/suspend", body);

            out.println("✓ " + text(org, "name") + " is suspended.");
            out.println("  Reason shown to them: " + text(org, "suspensionReason"));
            // Worth saying, because it is the surprising half and the reason support can help.
            out.println("  They can still sign in and read. Writes and ingest are refused.");
            return 0;
        }

        private static String quote(String value) {
            return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
    }

    @Command(name = "reinstate", description = "Lift a suspension", mixinStandardHelpOptions = true)
    public static class ReinstateCommand extends AdminSubcommand {

        @Parameters(index = "0", paramLabel = "<organizationId>", description = "The organization to reinstate")
        String organizationId;

        @Override
        Integer run(AdminApiClient client) throws Exception {
            JsonNode org = client.post("/api/v1/admin/organizations/" + organizationId + "/reinstate", null);
            out.println("✓ " + text(org, "name") + " is active again.");
            return 0;
        }
    }
}

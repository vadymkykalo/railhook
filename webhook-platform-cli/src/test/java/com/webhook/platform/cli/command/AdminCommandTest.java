package com.webhook.platform.cli.command;

import com.sun.net.httpserver.HttpExchange;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The operator back-office, from a terminal.
 *
 * <p>Three things are being held down, and only the first is about output.
 *
 * <p><strong>The credential never comes from the config file.</strong> Every other command here
 * reads a bearer token that {@code hookflow login} saved; this one must not, because the token
 * is the deployment's rather than a person's and is the same secret for every tenant on it. A
 * command that silently fell back to the saved login would either do nothing or, worse, appear
 * to work while sending the wrong credential.
 *
 * <p><strong>-1 means unlimited.</strong> It is how the whole plan catalog spells it, and the
 * dashboard already had to be taught not to print it as a number — a plan that allows minus one
 * project. A terminal has no styling to soften that.
 *
 * <p><strong>A suspension says what the tenant can still do.</strong> Reads keep working, which
 * is the surprising half and the reason support can talk to somebody who has been suspended.
 */
class AdminCommandTest extends CliCommandTestBase {

    private static final String TOKEN = "operator-token";

    private final List<String> seenTokens = new ArrayList<>();

    private void stub(String path, String body) {
        server.createContext(path, exchange -> {
            seenTokens.add(String.valueOf(exchange.getRequestHeaders().getFirst("X-Platform-Admin-Token")));
            respond(exchange, 200, body);
        });
    }

    private void stubRefusing(String path) {
        server.createContext(path, exchange -> respond(exchange, 403, "{}"));
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(payload);
        }
    }

    private static String org(String suspension) {
        return "{\"id\":\"11111111-1111-1111-1111-111111111111\",\"name\":\"Acme\",\"planName\":\"pro\","
                + "\"billingStatus\":\"ACTIVE\",\"createdAt\":\"2026-08-01T00:00:00Z\","
                + "\"projectCount\":3,\"memberCount\":5" + suspension + "}";
    }

    @Test
    void orgs_listsEveryTenantWithItsPlanAndState() throws Exception {
        stub("/api/v1/admin/organizations",
                "{\"content\":[" + org("") + "],\"totalElements\":1}");
        server.start();
        writeConfig(authenticatedConfig());

        int exitCode = run("admin", "orgs", "--token", TOKEN);

        assertEquals(0, exitCode);
        assertTrue(out().contains("Acme"), out());
        assertTrue(out().contains("pro"), out());
        assertEquals(List.of(TOKEN), seenTokens);
    }

    @Test
    void orgs_showsWhyASuspendedTenantIsSuspended() throws Exception {
        stub("/api/v1/admin/organizations",
                "{\"content\":[" + org(",\"suspendedAt\":\"2026-09-01T00:00:00Z\",\"suspensionReason\":\"spam reports\"")
                        + "],\"totalElements\":1}");
        server.start();
        writeConfig(authenticatedConfig());

        run("admin", "orgs", "--suspended", "--token", TOKEN);

        // The reason is the whole content of the row for a suspended tenant.
        assertTrue(out().contains("SUSPENDED"), out());
        assertTrue(out().contains("spam reports"), out());
    }

    @Test
    void org_printsUsageAgainstThePlan() throws Exception {
        stub("/api/v1/admin/organizations/org-1", org(""));
        stub("/api/v1/admin/organizations/org-1/usage",
                "{\"events\":{\"current\":2500,\"limit\":10000,\"percentUsed\":25.0},"
                        + "\"projects\":{\"current\":3,\"limit\":-1,\"percentUsed\":0.0},"
                        + "\"periodStart\":\"2026-09-01T00:00:00Z\",\"periodEnd\":\"2026-10-01T00:00:00Z\"}");
        server.start();
        writeConfig(authenticatedConfig());

        int exitCode = run("admin", "org", "org-1", "--token", TOKEN);

        assertEquals(0, exitCode);
        assertTrue(out().contains("2500 / 10000"), out());
        // -1 is unlimited. Printed raw it reads as a plan that allows minus one project.
        assertTrue(out().contains("unlimited"), out());
        assertFalse(out().contains("/ -1"), out());
    }

    @Test
    void org_stillShowsTheTenantWhenUsageCannotBeRead() throws Exception {
        stub("/api/v1/admin/organizations/org-1", org(""));
        stubRefusing("/api/v1/admin/organizations/org-1/usage");
        server.start();
        writeConfig(authenticatedConfig());

        int exitCode = run("admin", "org", "org-1", "--token", TOKEN);

        // Who this is and whether they are suspended is the part an operator opened this for.
        assertEquals(0, exitCode);
        assertTrue(out().contains("Acme"), out());
        assertTrue(out().contains("unavailable"), out());
    }

    @Test
    void suspend_saysWhatTheTenantCanStillDo() throws Exception {
        stub("/api/v1/admin/organizations/org-1/suspend",
                org(",\"suspendedAt\":\"2026-09-05T00:00:00Z\",\"suspensionReason\":\"spam reports\""));
        server.start();
        writeConfig(authenticatedConfig());

        int exitCode = run("admin", "suspend", "org-1", "--reason", "spam reports", "--token", TOKEN);

        assertEquals(0, exitCode);
        assertTrue(out().contains("spam reports"), out());
        // Reads keep working. An operator who does not know that tells the customer to wait.
        assertTrue(out().toLowerCase().contains("read"), out());
    }

    @Test
    void suspend_refusesWithoutAReason() throws Exception {
        server.start();
        writeConfig(authenticatedConfig());

        // The tenant is shown the reason, so a suspension without one is not a thing to allow
        // by accident from a shell.
        int exitCode = run("admin", "suspend", "org-1", "--token", TOKEN);

        assertTrue(exitCode != 0);
        assertTrue(seenTokens.isEmpty());
    }

    @Test
    void refusesToFallBackToTheSavedLogin() throws Exception {
        server.start();
        // A saved tenant login is present and must not be used: it is a different credential
        // for a different question, and sending it here would fail in a confusing way.
        writeConfig(authenticatedConfig());

        int exitCode = run("admin", "orgs");

        assertEquals(2, exitCode);
        assertTrue(err().contains("HOOKFLOW_ADMIN_TOKEN"), err());
        assertTrue(seenTokens.isEmpty());
    }

    @Test
    void reportsARefusedTokenAsSuch() throws Exception {
        stubRefusing("/api/v1/admin/organizations");
        server.start();
        writeConfig(authenticatedConfig());

        int exitCode = run("admin", "orgs", "--token", "wrong");

        assertEquals(1, exitCode);
        assertTrue(err().contains("403") || err().toLowerCase().contains("token"), err());
    }
}

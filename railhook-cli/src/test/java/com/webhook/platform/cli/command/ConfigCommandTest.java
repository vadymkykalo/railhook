package com.webhook.platform.cli.command;

import com.webhook.platform.cli.config.CliConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ConfigCommandTest extends CliCommandTestBase {

    @Test
    void show_defaultConfig_printsLocalhostAndNotAuthenticated() {
        int exitCode = run("config", "show");

        assertEquals(0, exitCode);
        assertTrue(out().contains("http://localhost:8080"));
        assertTrue(out().contains("not authenticated"));
    }

    @Test
    void show_authenticated_truncatesToken() throws Exception {
        CliConfig config = authenticatedConfig();
        config.setAccessToken("a-very-long-access-token-value-1234567890");
        writeConfig(config);

        int exitCode = run("config", "show");

        assertEquals(0, exitCode);
        String output = out();
        assertTrue(output.contains("authenticated"));
        // Token is truncated to the first 20 chars, followed by an ellipsis.
        assertTrue(output.contains("a-very-long-access-t…"));
        assertFalse(output.contains("1234567890"));
    }

    @Test
    void set_backendUrl_persistsToConfigFile() {
        int exitCode = run("config", "set", "backend-url", "https://staging.example.com");

        assertEquals(0, exitCode);
        assertTrue(out().contains("✓ Set backend-url = https://staging.example.com"));

        // Re-running "show" (fresh CliConfigService load) proves it was actually persisted.
        outContent.reset();
        run("config", "show");
        assertTrue(out().contains("https://staging.example.com"));
    }

    @Test
    void set_projectId_persists() {
        int exitCode = run("config", "set", "project-id", "proj-42");
        assertEquals(0, exitCode);

        outContent.reset();
        run("config", "show");
        assertTrue(out().contains("proj-42"));
    }

    @Test
    void set_unknownKey_returnsErrorExitCode() {
        int exitCode = run("config", "set", "bogus-key", "value");

        assertEquals(1, exitCode);
        assertTrue(err().contains("Unknown config key"));
    }

    @Test
    void clear_removesConfigFile() throws Exception {
        writeConfig(authenticatedConfig());

        int exitCode = run("config", "clear");

        assertEquals(0, exitCode);
        assertTrue(out().contains("cleared"));

        outContent.reset();
        run("config", "show");
        assertTrue(out().contains("not authenticated"));
    }

    @Test
    void profile_createAndUse_switchesActiveBackendUrl() {
        int createExit = run("config", "profile", "create", "staging", "--url", "https://staging.example.com");
        assertEquals(0, createExit);
        assertTrue(out().contains("Profile 'staging' created"));

        outContent.reset();
        int useExit = run("config", "profile", "use", "staging");
        assertEquals(0, useExit);
        assertTrue(out().contains("Switched to profile: staging"));
        assertTrue(out().contains("https://staging.example.com"));

        outContent.reset();
        run("config", "show");
        assertTrue(out().contains("https://staging.example.com"));
        assertTrue(out().contains("staging"));
    }

    @Test
    void profile_createDuplicate_returnsError() {
        run("config", "profile", "create", "staging");
        outContent.reset();

        int exitCode = run("config", "profile", "create", "staging");

        assertEquals(1, exitCode);
        assertTrue(err().contains("already exists"));
    }

    @Test
    void profile_useNonexistent_returnsError() {
        int exitCode = run("config", "profile", "use", "does-not-exist");

        assertEquals(1, exitCode);
        assertTrue(err().contains("not found"));
    }

    @Test
    void profile_list_showsDefaultAndCreatedProfiles() {
        run("config", "profile", "create", "staging", "--url", "https://staging.example.com");
        outContent.reset();

        int exitCode = run("config", "profile", "list");

        assertEquals(0, exitCode);
        String output = out();
        assertTrue(output.contains("default"));
        assertTrue(output.contains("staging"));
        assertTrue(output.contains("https://staging.example.com"));
    }

    @Test
    void profile_delete_removesProfile() {
        run("config", "profile", "create", "staging");
        outContent.reset();

        int exitCode = run("config", "profile", "delete", "staging");

        assertEquals(0, exitCode);
        assertTrue(out().contains("deleted"));

        outContent.reset();
        run("config", "profile", "list");
        assertFalse(out().contains("staging"));
    }

    @Test
    void profile_deleteDefault_isRejected() {
        int exitCode = run("config", "profile", "delete", "default");

        assertEquals(1, exitCode);
        assertTrue(err().contains("Cannot delete the default profile"));
    }

    @Test
    void profile_deleteNonexistent_returnsError() {
        int exitCode = run("config", "profile", "delete", "ghost");

        assertEquals(1, exitCode);
        assertTrue(err().contains("not found"));
    }
}

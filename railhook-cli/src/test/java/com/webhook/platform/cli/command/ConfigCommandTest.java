package com.webhook.platform.cli.command;

import com.webhook.platform.cli.config.CliConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

class ConfigCommandTest extends CliCommandTestBase {

    @Test
    void show_authenticated_truncatesToken() throws Exception {
        CliConfig config = authenticatedConfig();
        config.setAccessToken("a-very-long-access-token-value-1234567890");
        writeConfig(config);

        int exitCode = run("config", "show");

        assertEquals(0, exitCode);
        String output = out();
        assertTrue(output.contains("authenticated"));
        assertTrue(output.contains("a-very-long-access-t…"));
        assertFalse(output.contains("1234567890"));
    }

    @ParameterizedTest
    @CsvSource({"backend-url, https://staging.example.com", "project-id, proj-42"})
    void set_persistsToConfigFile(String key, String value) {
        int exitCode = run("config", "set", key, value);
        assertEquals(0, exitCode);

        outContent.reset();
        run("config", "show");
        assertTrue(out().contains(value));
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
        outContent.reset();
        run("config", "show");
        assertTrue(out().contains("not authenticated"));
    }

    @Test
    void profile_createAndUse_switchesActiveBackendUrl() {
        int createExit = run("config", "profile", "create", "staging", "--url", "https://staging.example.com");
        assertEquals(0, createExit);

        outContent.reset();
        int useExit = run("config", "profile", "use", "staging");
        assertEquals(0, useExit);

        outContent.reset();
        run("config", "show");
        assertTrue(out().contains("https://staging.example.com"));
        assertTrue(out().contains("staging"));
    }

    @Test
    void profile_delete_removesProfile() {
        run("config", "profile", "create", "staging");
        outContent.reset();

        int exitCode = run("config", "profile", "delete", "staging");

        assertEquals(0, exitCode);
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

    @ParameterizedTest
    @CsvSource({"create, staging", "use, does-not-exist", "delete, ghost"})
    void profile_invalidTarget_returnsError(String action, String name) {
        run("config", "profile", "create", "staging");
        outContent.reset();

        int exitCode = run("config", "profile", action, name);

        assertEquals(1, exitCode);
    }
}

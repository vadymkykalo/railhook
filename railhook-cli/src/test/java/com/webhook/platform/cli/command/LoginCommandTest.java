package com.webhook.platform.cli.command;

import com.sun.net.httpserver.HttpExchange;
import com.webhook.platform.cli.config.CliConfig;
import com.webhook.platform.cli.config.CliConfigService;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class LoginCommandTest extends CliCommandTestBase {

    private void writeUnauthenticatedConfig() throws Exception {
        CliConfig config = new CliConfig();
        config.setBackendUrl(backendUrl);
        writeConfig(config);
    }

    private CliConfig savedConfig() {
        return new CliConfigService(Path.of(System.getProperty("user.home"), ".config", "railhook", "config.json")).load();
    }

    // --password is an interactive picocli option: without a console it reads one line from stdin.
    private int runWithPasswordPrompt(String password, String... argsWithoutPassword) {
        InputStream originalIn = System.in;
        try {
            System.setIn(new ByteArrayInputStream((password + "\n").getBytes(StandardCharsets.UTF_8)));
            return run(argsWithoutPassword);
        } finally {
            System.setIn(originalIn);
        }
    }

    @Test
    void directLogin_withEmailAndPassword_savesTokensAndUserInfo() throws Exception {
        AtomicReference<String> capturedLoginBody = new AtomicReference<>();
        server.createContext("/api/v1/auth/login", exchange -> {
            capturedLoginBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respondJson(exchange, 200, "{\"accessToken\":\"acc-tok-1\",\"refreshToken\":\"ref-tok-1\"}");
        });
        server.createContext("/api/v1/auth/me", exchange ->
                respondJson(exchange, 200,
                        "{\"user\":{\"id\":\"user-9\"},\"organization\":{\"id\":\"org-9\"}}"));
        server.start();
        writeUnauthenticatedConfig();

        int exitCode = runWithPasswordPrompt("hunter2", "login", "--email", "dev@example.com", "--password");

        assertEquals(0, exitCode);
        assertTrue(capturedLoginBody.get().contains("dev@example.com"));
        assertTrue(capturedLoginBody.get().contains("hunter2"));

        CliConfig saved = savedConfig();
        assertEquals("acc-tok-1", saved.getAccessToken());
        assertEquals("ref-tok-1", saved.getRefreshToken());
        assertEquals("user-9", saved.getUserId());
        assertEquals("org-9", saved.getOrganizationId());
        assertTrue(saved.isAuthenticated());
    }

    @Test
    void directLogin_invalidCredentials_returnsErrorExitCode() throws Exception {
        server.createContext("/api/v1/auth/login", exchange ->
                respondJson(exchange, 401, "{\"message\":\"Invalid credentials\"}"));
        server.start();
        writeUnauthenticatedConfig();

        int exitCode = runWithPasswordPrompt("wrong", "login", "--email", "dev@example.com", "--password");

        assertNotEquals(0, exitCode);
    }

    @Test
    void directLogin_meLookupFails_stillSavesTokenNonCritically() throws Exception {
        server.createContext("/api/v1/auth/login", exchange ->
                respondJson(exchange, 200, "{\"accessToken\":\"acc-tok-3\"}"));
        server.createContext("/api/v1/auth/me", exchange -> exchange.sendResponseHeaders(500, -1));
        server.start();
        writeUnauthenticatedConfig();

        int exitCode = runWithPasswordPrompt("hunter2", "login", "--email", "dev@example.com", "--password");

        assertEquals(0, exitCode, "the /me lookup is best-effort — its failure must not fail the login");
        CliConfig saved = savedConfig();
        assertEquals("acc-tok-3", saved.getAccessToken());
        assertNull(saved.getUserId());
    }

    private static void respondJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] resp = json.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, resp.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(resp);
        }
    }
}

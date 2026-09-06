package com.webhook.platform.cli.command;

import com.webhook.platform.cli.config.CliConfig;
import com.webhook.platform.cli.config.CliConfigService;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class LoginCommandTest extends CliCommandTestBase {

    private void respondJson(String path, int status, String json) {
        server.createContext(path, exchange -> {
            byte[] resp = json.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
    }

    private void writeUnauthenticatedConfig() throws Exception {
        CliConfig config = new CliConfig();
        config.setBackendUrl(backendUrl);
        writeConfig(config);
    }

    /**
     * --password is a picocli {@code interactive = true} option: even when a value
     * follows it on the command line, picocli's interactive-option handling always
     * prompts — via {@code System.console()} if attached, otherwise falling back to
     * reading one line from {@code System.in}. There is no attached console in a
     * forked Maven test JVM, so the value must be supplied by redirecting stdin, not
     * as a plain CLI argument (a plain argument after {@code --password} just hangs
     * waiting on a prompt that's already been satisfied by the token it consumed as
     * the "prompt", which is why this runs {@code run()} inside stdin redirection
     * rather than passing the password positionally).
     */
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
            respondJsonInline(exchange, 200, "{\"accessToken\":\"acc-tok-1\",\"refreshToken\":\"ref-tok-1\"}");
        });
        server.createContext("/api/v1/auth/me", exchange ->
                respondJsonInline(exchange, 200,
                        "{\"user\":{\"id\":\"user-9\"},\"organization\":{\"id\":\"org-9\"}}"));
        server.start();
        writeUnauthenticatedConfig();

        int exitCode = runWithPasswordPrompt("hunter2", "login", "--email", "dev@example.com", "--password");

        assertEquals(0, exitCode);
        assertTrue(out().contains("Logged in successfully"));
        assertTrue(capturedLoginBody.get().contains("dev@example.com"));
        assertTrue(capturedLoginBody.get().contains("hunter2"));

        // Verify the token + fetched identity were actually persisted to disk.
        Path configPath = Path.of(System.getProperty("user.home"), ".config", "railhook", "config.json");
        CliConfigService configService = new CliConfigService(configPath);
        CliConfig saved = configService.load();
        assertEquals("acc-tok-1", saved.getAccessToken());
        assertEquals("ref-tok-1", saved.getRefreshToken());
        assertEquals("user-9", saved.getUserId());
        assertEquals("org-9", saved.getOrganizationId());
        assertTrue(saved.isAuthenticated());
    }

    @Test
    void directLogin_serverOption_overridesBackendUrlBeforeLoggingIn() throws Exception {
        server.createContext("/api/v1/auth/login", exchange ->
                respondJsonInline(exchange, 200, "{\"accessToken\":\"acc-tok-2\"}"));
        server.createContext("/api/v1/auth/me", exchange ->
                respondJsonInline(exchange, 200, "{}"));
        server.start();
        writeUnauthenticatedConfig();

        int exitCode = runWithPasswordPrompt("hunter2", "login", "--server", backendUrl, "--email", "dev@example.com", "--password");

        assertEquals(0, exitCode);
        Path configPath = Path.of(System.getProperty("user.home"), ".config", "railhook", "config.json");
        CliConfig saved = new CliConfigService(configPath).load();
        assertEquals(backendUrl, saved.getBackendUrl());
        assertEquals("acc-tok-2", saved.getAccessToken());
    }

    @Test
    void directLogin_invalidCredentials_returnsErrorExitCode() throws Exception {
        server.createContext("/api/v1/auth/login", exchange ->
                respondJsonInline(exchange, 401, "{\"message\":\"Invalid credentials\"}"));
        server.start();
        writeUnauthenticatedConfig();

        int exitCode = runWithPasswordPrompt("wrong", "login", "--email", "dev@example.com", "--password");

        assertNotEquals(0, exitCode);
    }

    @Test
    void directLogin_meLookupFails_stillSavesTokenNonCritically() throws Exception {
        server.createContext("/api/v1/auth/login", exchange ->
                respondJsonInline(exchange, 200, "{\"accessToken\":\"acc-tok-3\"}"));
        server.createContext("/api/v1/auth/me", exchange -> exchange.sendResponseHeaders(500, -1));
        server.start();
        writeUnauthenticatedConfig();

        int exitCode = runWithPasswordPrompt("hunter2", "login", "--email", "dev@example.com", "--password");

        assertEquals(0, exitCode, "the /me lookup is best-effort — its failure must not fail the login");
        Path configPath = Path.of(System.getProperty("user.home"), ".config", "railhook", "config.json");
        CliConfig saved = new CliConfigService(configPath).load();
        assertEquals("acc-tok-3", saved.getAccessToken());
        assertNull(saved.getUserId());
    }

    private static void respondJsonInline(com.sun.net.httpserver.HttpExchange exchange, int status, String json) throws java.io.IOException {
        byte[] resp = json.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, resp.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(resp);
        }
    }
}

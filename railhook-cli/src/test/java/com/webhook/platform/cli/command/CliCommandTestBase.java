package com.webhook.platform.cli.command;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.sun.net.httpserver.HttpServer;
import com.webhook.platform.cli.RailhookCli;
import com.webhook.platform.cli.config.CliConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Shared setup for CLI command tests.
 *
 * <p>None of the command classes ({@code StatusCommand}, {@code ReplayCommand}, etc.)
 * take a {@code CliConfigService} via constructor injection — they each do
 * {@code new CliConfigService()} internally, which resolves the config file from
 * (in order) {@code XDG_CONFIG_HOME}, {@code RAILHOOK_CONFIG}, or
 * {@code ~/.config/railhook/config.json}. Neither env var is set in CI or in this
 * sandbox, so redirecting the {@code user.home} system property to a JUnit
 * {@code @TempDir} is the only way to point a command at a throwaway config
 * without touching production code — this must happen *before*
 * {@code new CommandLine(new RailhookCli())}, because picocli eagerly
 * instantiates every declared subcommand (and each one's
 * {@code PrintStream out = System.out} field initializer) while building the
 * command tree, not lazily when a subcommand is actually invoked.
 */
abstract class CliCommandTestBase {

    @TempDir
    Path tempDir;

    protected ByteArrayOutputStream outContent;
    protected ByteArrayOutputStream errContent;
    private PrintStream originalOut;
    private PrintStream originalErr;
    private String originalUserHome;

    protected HttpServer server;
    protected String backendUrl;

    private static final ObjectMapper CONFIG_MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    @BeforeEach
    void redirectHomeAndStreams() throws Exception {
        originalUserHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.toString());

        originalOut = System.out;
        originalErr = System.err;
        outContent = new ByteArrayOutputStream();
        errContent = new ByteArrayOutputStream();
        System.setOut(new PrintStream(outContent, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(errContent, true, StandardCharsets.UTF_8));

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        backendUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void restoreHomeAndStreams() {
        if (server != null) {
            server.stop(0);
        }
        System.setOut(originalOut);
        System.setErr(originalErr);
        if (originalUserHome != null) {
            System.setProperty("user.home", originalUserHome);
        } else {
            System.clearProperty("user.home");
        }
    }

    /** Writes a config file at the redirected ~/.config/railhook/config.json. */
    protected void writeConfig(CliConfig config) throws Exception {
        Path configPath = tempDir.resolve(".config").resolve("railhook").resolve("config.json");
        Files.createDirectories(configPath.getParent());
        CONFIG_MAPPER.writeValue(configPath.toFile(), config);
    }

    /** Builds a config pointed at this test's local stub server, with the given auth state. */
    protected CliConfig authenticatedConfig() {
        CliConfig config = new CliConfig();
        config.setBackendUrl(backendUrl);
        config.setAccessToken("test-access-token");
        config.setRefreshToken("test-refresh-token");
        config.setUserId("user-1");
        config.setOrganizationId("org-1");
        return config;
    }

    /** Runs the CLI with the given args, capturing System.out/System.err. Returns the exit code. */
    protected int run(String... args) {
        CommandLine cmd = new CommandLine(new RailhookCli());
        return cmd.execute(args);
    }

    protected String out() {
        return outContent.toString(StandardCharsets.UTF_8);
    }

    protected String err() {
        return errContent.toString(StandardCharsets.UTF_8);
    }
}

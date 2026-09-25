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

// Commands build their own CliConfigService, so user.home is redirected before picocli builds the tree.
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

    protected void writeConfig(CliConfig config) throws Exception {
        Path configPath = tempDir.resolve(".config").resolve("railhook").resolve("config.json");
        Files.createDirectories(configPath.getParent());
        CONFIG_MAPPER.writeValue(configPath.toFile(), config);
    }

    protected CliConfig authenticatedConfig() {
        CliConfig config = new CliConfig();
        config.setBackendUrl(backendUrl);
        config.setAccessToken("test-access-token");
        config.setRefreshToken("test-refresh-token");
        config.setUserId("user-1");
        config.setOrganizationId("org-1");
        return config;
    }

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

package com.webhook.platform.worker.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

// Nothing activated the production profile, so the JSON appender was dead; both files must agree.
@Tag("ratchet")
class LoggingProfileActivationTest {

    private static final Pattern PROFILE_FROM_APP_ENV = Pattern.compile(
            "profiles:\\s*\\n\\s*active:\\s*\\$\\{APP_ENV:[a-z]+}");

    private static final Pattern PRODUCTION_APPENDER = Pattern.compile(
            "<springProfile name=\"production\">");

    @Test
    @DisplayName("the worker activates a Spring profile from APP_ENV")
    void workerActivatesProfileFromAppEnv() throws IOException {
        assertActivatesProfile(Paths.get("src/main/resources/application.yml"), "the worker");
    }

    @Test
    @DisplayName("the api activates a Spring profile from APP_ENV")
    void apiActivatesProfileFromAppEnv() throws IOException {
        assertActivatesProfile(Paths.get("..", "railhook-api", "src", "main", "resources", "application.yml"),
                "the api");
    }

    @Test
    @DisplayName("both logback configurations still key their JSON appender on that profile")
    void bothLogbackConfigsKeyOnProduction() throws IOException {
        assertKeysOnProduction(Paths.get("src/main/resources/logback-spring.xml"), "the worker");
        assertKeysOnProduction(
                Paths.get("..", "railhook-api", "src", "main", "resources", "logback-spring.xml"), "the api");
    }

    private static void assertActivatesProfile(Path applicationYml, String who) throws IOException {
        assertTrue(PROFILE_FROM_APP_ENV.matcher(read(applicationYml)).find(),
                who + "'s application.yml no longer binds spring.profiles.active to APP_ENV. Without it "
                        + "the `production` profile is never active, and logback-spring.xml's JSON appender "
                        + "— which the observability guide tells operators to expect — never runs.");
    }

    private static void assertKeysOnProduction(Path logbackXml, String who) throws IOException {
        assertTrue(PRODUCTION_APPENDER.matcher(read(logbackXml)).find(),
                who + "'s logback-spring.xml no longer has a <springProfile name=\"production\"> block. If "
                        + "the appender moved, the APP_ENV binding in application.yml is now selecting nothing.");
    }

    private static String read(Path path) throws IOException {
        assertTrue(Files.exists(path), path + " is missing");
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}

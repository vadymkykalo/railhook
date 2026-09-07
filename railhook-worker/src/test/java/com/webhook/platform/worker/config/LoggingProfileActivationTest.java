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

/**
 * Ratchet over the one thing that decides whether a production deployment logs JSON.
 *
 * <p>{@code logback-spring.xml} picks LogstashEncoder under the {@code production} Spring
 * profile. Nothing ever activated that profile: {@code APP_ENV=production} is an ordinary
 * property, and {@code SPRING_PROFILES_ACTIVE} appeared nowhere in the repository — not in
 * compose, not in the chart, not in install.sh. So the JSON appender was written, committed,
 * documented in the observability guide, and dead, and promtail's {@code json} stage parsed
 * plain text forever, which is why the {@code level} label never showed up in Loki.
 *
 * <p>Two files have to agree for it to work, and neither references the other. This checks
 * both, in both services.
 */
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

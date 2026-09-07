package com.webhook.platform.api.ops;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ratchet over the framework version, which the build sets and two documents repeat.
 *
 * <p>The README badge and CLAUDE.md's first line both said Spring Boot 3.5 well after the
 * upgrade to 4.1 — the badge is the first thing anyone evaluating this project reads, and it was
 * describing a different platform. Nothing connected them to the pom, so nothing could have
 * caught it; the version was simply typed three times and changed once.
 *
 * <p>Major and minor only. The patch moves with every dependency bump and neither document is
 * making a claim that fine.
 */
@Tag("ratchet")
class DeclaredStackVersionTest {

    private static final Path POM = Paths.get("..", "pom.xml");
    private static final Path README = Paths.get("..", "README.md");
    private static final Path CLAUDE_MD = Paths.get("..", "CLAUDE.md");

    private static final Pattern POM_VERSION =
            Pattern.compile("<spring-boot\\.version>(\\d+)\\.(\\d+)\\.");
    /** The shields.io badge: `Spring%20Boot-4.1-green`. */
    private static final Pattern README_BADGE =
            Pattern.compile("Spring%20Boot-(\\d+)\\.(\\d+)-");
    private static final Pattern CLAUDE_PROSE =
            Pattern.compile("Spring Boot (\\d+)\\.(\\d+)");

    @Test
    @DisplayName("the README badge names the Spring Boot the build actually uses")
    void readmeBadgeMatchesThePom() throws IOException {
        assertEquals(pomVersion(), match(README_BADGE, read(README), "README.md has no Spring Boot badge"),
                "The README badge is the first thing an evaluator reads. It named a version this "
                        + "project has not been on since the upgrade — change it with the pom, or not at all.");
    }

    @Test
    @DisplayName("CLAUDE.md names it too, since it is the first line an agent reads")
    void claudeMdMatchesThePom() throws IOException {
        assertEquals(pomVersion(), match(CLAUDE_PROSE, read(CLAUDE_MD), "CLAUDE.md does not name Spring Boot"),
                "CLAUDE.md's opening line is what every agent takes as the truth about this stack.");
    }

    private static String pomVersion() throws IOException {
        return match(POM_VERSION, read(POM), "pom.xml declares no <spring-boot.version>");
    }

    private static String match(Pattern pattern, String text, String absenceMessage) {
        Matcher m = pattern.matcher(text);
        assertTrue(m.find(), absenceMessage);
        return m.group(1) + "." + m.group(2);
    }

    private static String read(Path path) throws IOException {
        assertTrue(Files.exists(path), path + " is missing");
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}

package com.webhook.platform.api.domain;

import com.webhook.platform.common.retry.RetryLadder;
import com.webhook.platform.common.retry.RetryLadderDefaults;
import com.webhook.platform.common.retry.RetryableStatuses;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// SQL cannot reference a Java constant, so only this keeps column defaults and RetryLadderDefaults equal.
@Tag("ratchet")
class SchemaRetryLadderDefaultsTest {

    private static final Pattern RETRY_DELAYS_DEFAULT = Pattern.compile(
            "retry_delays\\s+TEXT\\s+(?:NOT\\s+NULL\\s+)?DEFAULT\\s+'([^']+)'", Pattern.CASE_INSENSITIVE);

    private static final Pattern MAX_ATTEMPTS_DEFAULT = Pattern.compile(
            "max_attempts\\s+INTEGER\\s+(?:NOT\\s+NULL\\s+)?DEFAULT\\s+(\\d+)", Pattern.CASE_INSENSITIVE);

    private static final Pattern RETRYABLE_STATUSES_DEFAULT = Pattern.compile(
            "retryable_statuses\\s+TEXT\\s+(?:NOT\\s+NULL\\s+)?DEFAULT\\s+'([^']+)'", Pattern.CASE_INSENSITIVE);

    private static final Path MIGRATIONS = Paths.get("src/main/resources/db/migration");

    @Test
    @DisplayName("V001's outgoing ladder defaults match RetryLadderDefaults.OUTGOING_*")
    void outgoingSchemaDefaultsMatchConstants() throws IOException {
        String v001 = read("V001__initial_schema.sql");

        List<String> ladders = allMatches(RETRY_DELAYS_DEFAULT, v001);
        assertFalse(ladders.isEmpty(), "V001 declares no retry_delays default — did the column move?");
        for (String ladder : ladders) {
            assertEquals(RetryLadderDefaults.OUTGOING_DELAYS, ladder,
                    "V001 retry_delays default has drifted from RetryLadderDefaults.OUTGOING_DELAYS. "
                            + "Change both, or neither.");
        }

        List<String> attempts = allMatches(MAX_ATTEMPTS_DEFAULT, v001);
        assertFalse(attempts.isEmpty(), "V001 declares no max_attempts default — did the column move?");
        for (String attempt : attempts) {
            assertEquals(RetryLadderDefaults.OUTGOING_MAX_ATTEMPTS, Integer.parseInt(attempt),
                    "V001 max_attempts default has drifted from RetryLadderDefaults.OUTGOING_MAX_ATTEMPTS.");
        }
    }

    @Test
    @DisplayName("V005's incoming ladder defaults match RetryLadderDefaults.INCOMING_*")
    void incomingSchemaDefaultsMatchConstants() throws IOException {
        String v005 = read("V005__incoming_webhooks.sql");

        List<String> ladders = allMatches(RETRY_DELAYS_DEFAULT, v005);
        assertFalse(ladders.isEmpty(), "V005 declares no retry_delays default — did the column move?");
        for (String ladder : ladders) {
            assertEquals(RetryLadderDefaults.INCOMING_DELAYS, ladder,
                    "V005 retry_delays default has drifted from RetryLadderDefaults.INCOMING_DELAYS. "
                            + "Change both, or neither.");
        }

        List<String> attempts = allMatches(MAX_ATTEMPTS_DEFAULT, v005);
        assertFalse(attempts.isEmpty(), "V005 declares no max_attempts default — did the column move?");
        for (String attempt : attempts) {
            assertEquals(RetryLadderDefaults.INCOMING_MAX_ATTEMPTS, Integer.parseInt(attempt),
                    "V005 max_attempts default has drifted from RetryLadderDefaults.INCOMING_MAX_ATTEMPTS.");
        }
    }

    @Test
    @DisplayName("no later migration silently redefines a retry ladder default")
    void noLaterMigrationRedefinesALadder() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.list(MIGRATIONS)) {
            for (Path file : files.sorted().toList()) {
                String name = file.getFileName().toString();
                if (name.equals("V001__initial_schema.sql") || name.equals("V005__incoming_webhooks.sql")) {
                    continue;
                }
                String sql = Files.readString(file, StandardCharsets.UTF_8);
                for (String ladder : allMatches(RETRY_DELAYS_DEFAULT, sql)) {
                    if (!ladder.equals(RetryLadderDefaults.OUTGOING_DELAYS)
                            && !ladder.equals(RetryLadderDefaults.INCOMING_DELAYS)) {
                        offenders.add(name + " sets retry_delays default to '" + ladder + "'");
                    }
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "A migration introduced a retry ladder default that matches neither direction's "
                        + "declared default. Add it to RetryLadderDefaults or align it:\n  "
                        + String.join("\n  ", offenders));
    }

    // A Subscription's copy lands on its Deliveries and a Destination's is read directly, so all three must agree.
    @Test
    @DisplayName("every retryable_statuses default in the schema matches RetryableStatuses.DEFAULT_SPEC")
    void retryableStatusDefaultsMatchTheConstant() throws IOException {
        List<String> offenders = new ArrayList<>();
        int found = 0;
        try (Stream<Path> files = Files.list(MIGRATIONS)) {
            for (Path file : files.sorted().toList()) {
                String sql = Files.readString(file, StandardCharsets.UTF_8);
                for (String spec : allMatches(RETRYABLE_STATUSES_DEFAULT, sql)) {
                    found++;
                    if (!spec.equals(RetryableStatuses.DEFAULT_SPEC)) {
                        offenders.add(file.getFileName() + " sets retryable_statuses default to '"
                                + spec + "'");
                    }
                }
            }
        }
        assertTrue(found > 0, "no migration declares a retryable_statuses default — did the column move?");
        assertTrue(offenders.isEmpty(),
                "A migration's retryable_statuses default has drifted from "
                        + "RetryableStatuses.DEFAULT_SPEC (\"" + RetryableStatuses.DEFAULT_SPEC
                        + "\"). Change both, or neither:\n  " + String.join("\n  ", offenders));
    }

    @Test
    @DisplayName("the declared retryable-status default is itself a valid spec")
    void declaredRetryableStatusDefaultParses() {
        assertDoesNotThrow(() -> RetryableStatuses.parse(RetryableStatuses.DEFAULT_SPEC));
    }

    @Test
    @DisplayName("both declared defaults are themselves valid ladders")
    void declaredDefaultsParse() {
        assertDoesNotThrow(() -> RetryLadder.parse(
                RetryLadderDefaults.OUTGOING_DELAYS, RetryLadderDefaults.OUTGOING_MAX_ATTEMPTS));
        assertDoesNotThrow(() -> RetryLadder.parse(
                RetryLadderDefaults.INCOMING_DELAYS, RetryLadderDefaults.INCOMING_MAX_ATTEMPTS));
    }

    private static String read(String migration) throws IOException {
        Path file = MIGRATIONS.resolve(migration);
        assertTrue(Files.exists(file), "migration not found: " + file.toAbsolutePath());
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    private static List<String> allMatches(Pattern pattern, String sql) {
        List<String> found = new ArrayList<>();
        Matcher m = pattern.matcher(sql);
        while (m.find()) {
            found.add(m.group(1));
        }
        return found;
    }
}

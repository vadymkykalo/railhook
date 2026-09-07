package com.webhook.platform.api.ops;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ratchet over the pg_dump flags, which are written down twice and must not diverge.
 *
 * <p>A packaged Helm chart only ships files under its own directory, so the CronJob cannot
 * source {@code deploy/scripts/db-backup.sh} — the script the Makefile and Compose paths use.
 * Both files say, in a comment, to keep the flags identical, and the script's comment promised
 * that {@code make verify-backup-parity} checked it. No such target existed, in the Makefile or
 * in CI. This is that check.
 *
 * <p>Why it matters more than it looks: {@code -Fc} is what makes the dump restorable with
 * {@code pg_restore} at all, and {@code --no-owner --no-privileges} are what let it restore into
 * a database whose roles differ from the source — which is every disaster-recovery restore there
 * is. A dump taken without them looks fine until the day it is needed.
 */
@Tag("ratchet")
class BackupFlagParityTest {

    private static final Path SCRIPT = Paths.get("..", "deploy", "scripts", "db-backup.sh");
    private static final Path CRONJOB =
            Paths.get("..", "deploy", "helm", "railhook", "templates", "db-backup-cronjob.yaml");

    /** The flags that decide whether a dump can be restored, and where. */
    private static final Set<String> REQUIRED_FLAGS = Set.of("-Fc", "--no-owner", "--no-privileges");

    /** How many modes db-backup.sh offers: embedded, external, direct. */
    private static final int SCRIPT_MODES = 3;

    @Test
    @DisplayName("every pg_dump in the Compose script uses the restorable flag set")
    void scriptInvocationsCarryTheFlags() throws IOException {
        String script = read(SCRIPT);

        // `pg_dump -h "` and `pg_dump -U "` are the real invocations; the quote is what keeps this
        // from counting the mode's own progress `echo`, which also says "pg_dump ->".
        int commands = countOccurrences(script, "pg_dump -h \"") + countOccurrences(script, "pg_dump -U \"");
        assertEquals(SCRIPT_MODES, commands,
                "expected the embedded, external and direct modes to each run pg_dump; found " + commands
                        + ". If a mode was added or removed, update SCRIPT_MODES with it — and check the "
                        + "new one carries the same flags.");

        // Trailing `-f` so the file's own comment, which quotes the same flags to explain the
        // pairing, is not counted as a fourth invocation.
        int flagged = countOccurrences(script, "-Fc --no-owner --no-privileges -f");
        assertEquals(SCRIPT_MODES, flagged,
                "one of db-backup.sh's pg_dump invocations does not carry `-Fc --no-owner --no-privileges`. "
                        + "-Fc is what makes the dump restorable with pg_restore; the other two are what let "
                        + "it restore into a database whose roles differ from the source, which is every real "
                        + "recovery. A dump taken without them looks fine until the day it is needed.");
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int at = haystack.indexOf(needle);
        while (at >= 0) {
            count++;
            at = haystack.indexOf(needle, at + needle.length());
        }
        return count;
    }

    @Test
    @DisplayName("the chart's CronJob uses the same flag set as the script")
    void cronJobCarriesTheSameFlags() throws IOException {
        String cronjob = read(CRONJOB);
        assertTrue(cronjob.contains("pg_dump"), CRONJOB + " no longer runs pg_dump — has the backup moved?");

        for (String flag : REQUIRED_FLAGS) {
            assertTrue(cronjob.contains(flag),
                    "the chart's backup CronJob is missing " + flag + ", which deploy/scripts/db-backup.sh "
                            + "uses. A Kubernetes backup and a Compose backup must restore the same way — "
                            + "without --no-owner and --no-privileges a dump will not restore into a database "
                            + "whose roles differ from the source, which is every real recovery.");
        }
    }

    @Test
    @DisplayName("both files still tell the next person to keep them in step")
    void bothFilesStillCarryTheWarning() throws IOException {
        assertTrue(read(SCRIPT).contains("Keep the pg_dump flags identical"),
                SCRIPT + " lost the comment pairing it with the chart's CronJob");
        assertTrue(read(CRONJOB).contains("Keep the pg_dump flags identical"),
                CRONJOB + " lost the comment pairing it with deploy/scripts/db-backup.sh");
    }

    private static String read(Path path) throws IOException {
        assertTrue(Files.exists(path), path + " is missing");
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}

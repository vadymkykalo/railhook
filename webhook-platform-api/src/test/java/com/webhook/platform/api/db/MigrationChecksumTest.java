package com.webhook.platform.api.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.fail;

/**
 * A migration that has shipped is never edited again.
 *
 * <p>Flyway records a checksum of every migration it applies and validates it on the next
 * start. The checksum covers the whole file, comments included, so changing one word of a
 * comment in a migration that has already run is enough: the application refuses to start,
 * on every deployment that has it, with a validation error naming a file nobody thinks they
 * changed. It is not repairable from the application — it needs a `flyway repair` against
 * production.
 *
 * <p>This is not hypothetical. A repository-wide rename touched a comment in
 * {@code V062__endpoint_signature_scheme.sql}; the compiler was happy, every test passed and
 * {@code make ratchets} was green, because nothing in the build had an opinion about
 * migrations changing. It was caught by reading a diff, which is not a control.
 *
 * <p><b>What this does and does not claim.</b> The hashes here are not Flyway's — Flyway's
 * are CRC32 and internal to it. Reproducing them would tie this test to a Flyway version for
 * no gain, because the question is not "what number does Flyway hold" but "did this file
 * change". What is reproduced is the one thing Flyway ignores: line endings. A file rewritten
 * CRLF is not a change to Flyway and is not a change here.
 *
 * <p><b>To regenerate</b> after adding a migration, or after a deliberate edit to one that has
 * not shipped:
 *
 * <pre>
 *   mvn test -pl webhook-platform-api -am -Dtest=MigrationChecksumTest -Dmigrations.regenerate=true
 * </pre>
 *
 * <p>Commit the result with the migration. Two different diffs come out of that, and they read
 * very differently on review: a new migration adds a line, while an edit to an existing one
 * changes a line and adds nothing — which is the signal this exists to produce.
 *
 * <p>Deliberately a plain {@code *Test} — it reads files and needs no Docker. See
 * {@code scripts/check-test-routing.sh}.
 */
@Tag("ratchet")
@DisplayName("Applied migrations are never edited")
class MigrationChecksumTest {

    private static final Path MIGRATIONS = Paths.get("src/main/resources/db/migration");
    private static final Path MANIFEST = Paths.get("src/test/resources/db/migration-checksums.txt");
    private static final String REGENERATE_PROPERTY = "migrations.regenerate";

    private static final String HEADER = """
            # Checksums of every Flyway migration, so that editing one that has already been
            # applied fails here rather than at startup on somebody's deployment.
            #
            # Generated. Do not hand-edit:
            #   mvn test -pl webhook-platform-api -am -Dtest=MigrationChecksumTest -Dmigrations.regenerate=true
            #
            # A changed line means an existing migration was modified. That is almost always
            # wrong: Flyway will reject it on every database that already ran it, and the fix
            # is a `flyway repair` in production rather than anything in this branch. Write a
            # new migration instead. See the class javadoc for the one case where changing a
            # line is legitimate.
            """;

    @Test
    void everyMigrationStillHashesToWhatWasCommitted() throws IOException {
        Map<String, String> onDisk = hashMigrations();

        if (Boolean.getBoolean(REGENERATE_PROPERTY)) {
            regenerate(onDisk);
            return;
        }

        Map<String, String> committed = readManifest();

        List<String> modified = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        List<String> added = new ArrayList<>();

        committed.forEach((name, hash) -> {
            String current = onDisk.get(name);
            if (current == null) {
                removed.add(name);
            } else if (!current.equals(hash)) {
                modified.add(name);
            }
        });
        onDisk.keySet().stream().filter(name -> !committed.containsKey(name)).forEach(added::add);

        if (modified.isEmpty() && removed.isEmpty() && added.isEmpty()) {
            return;
        }

        StringBuilder message = new StringBuilder();

        // Ordered worst first: a modified or removed migration is a production incident waiting
        // for the next restart, while an unrecorded new one is only bookkeeping.
        if (!modified.isEmpty()) {
            message.append("\nThese migrations were modified after being committed:\n");
            modified.forEach(name -> message.append("  ").append(name).append('\n'));
            message.append("""
                    
                    Flyway validates the checksum of every migration it has applied, over the
                    whole file including comments. Any deployment that already ran these will
                    refuse to start, and cannot be fixed from the application.
                    
                    Write a new migration instead. If the change genuinely cannot have been
                    applied anywhere - the migration is unreleased, and nobody has run this
                    branch against a database they keep - regenerate the manifest and say so in
                    the commit message.
                    """);
        }
        if (!removed.isEmpty()) {
            message.append("\nThese migrations were deleted or renamed:\n");
            removed.forEach(name -> message.append("  ").append(name).append('\n'));
            message.append("""
                    
                    A renamed migration is a new one to Flyway, and the old name stays in
                    flyway_schema_history forever. Deleting an applied migration makes every
                    existing database unvalidatable.
                    """);
        }
        if (!added.isEmpty()) {
            message.append("\nThese migrations are not recorded yet:\n");
            added.forEach(name -> message.append("  ").append(name).append('\n'));
        }

        message.append("""
                
                Regenerate with:
                  mvn test -pl webhook-platform-api -am -Dtest=MigrationChecksumTest -Dmigrations.regenerate=true
                """);

        fail(message.toString());
    }

    private Map<String, String> hashMigrations() throws IOException {
        try (Stream<Path> files = Files.list(MIGRATIONS)) {
            Map<String, String> hashes = new LinkedHashMap<>();
            files.filter(p -> p.getFileName().toString().endsWith(".sql"))
                    .sorted()
                    .forEach(p -> hashes.put(p.getFileName().toString(), hash(p)));
            return hashes;
        }
    }

    /**
     * SHA-256 over the file's lines joined with {@code \n}.
     *
     * <p>Reading by line rather than by byte is the whole point: it makes the hash blind to
     * line endings, which is the one difference Flyway is also blind to. Hashing the raw bytes
     * would fire on a checkout that normalised CRLF, which is a false alarm, and a ratchet that
     * cries wolf gets regenerated on red without being read.
     */
    private String hash(Path file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(String.join("\n", Files.readAllLines(file, StandardCharsets.UTF_8))
                    .getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every JVM", e);
        }
    }

    private Map<String, String> readManifest() throws IOException {
        if (!Files.exists(MANIFEST)) {
            fail("%s is missing. Generate it: mvn test -pl webhook-platform-api -am -Dtest=%s -D%s=true"
                    .formatted(MANIFEST, getClass().getSimpleName(), REGENERATE_PROPERTY));
        }
        Map<String, String> entries = new LinkedHashMap<>();
        for (String line : Files.readAllLines(MANIFEST, StandardCharsets.UTF_8)) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            String[] parts = trimmed.split("\\s+", 2);
            if (parts.length == 2) {
                entries.put(parts[0], parts[1]);
            }
        }
        return entries;
    }

    private void regenerate(Map<String, String> hashes) throws IOException {
        Files.createDirectories(MANIFEST.getParent());
        StringBuilder out = new StringBuilder(HEADER);
        hashes.forEach((name, hash) -> out.append(name).append("  ").append(hash).append('\n'));
        Files.writeString(MANIFEST, out.toString(), StandardCharsets.UTF_8);
        System.out.println("Wrote " + MANIFEST + " (" + hashes.size() + " migrations).");
    }
}

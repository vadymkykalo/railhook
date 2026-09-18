package com.webhook.platform.api.ops;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ratchet over what {@code railhook monitoring update} — run by every upgrade — does to a running
 * monitoring stack.
 *
 * <p>The fetch replaces {@code monitoring/} with a new directory, and each container mounts files
 * of the old one: the running Prometheus keeps reading the old rules, and a reload re-reads the
 * same old inode. A plain {@code up -d} changes nothing either, because the Compose file did not.
 * On railhook.io, Prometheus was still evaluating the rules it started with on 2026-09-13 five
 * releases later.
 */
@Tag("ratchet")
class MonitoringUpdateTest {

    private static final Path INSTALLER = Paths.get("..", "install.sh");

    @Test
    @DisplayName("an update that changes the files recreates the running containers onto them")
    void changedFilesRecreateTheStack() throws IOException {
        String update = updateBranch();
        assertTrue(update.contains("--force-recreate"),
                "install.sh's `monitoring update` does not recreate the running containers. They mount files of "
                        + "the directory the fetch just replaced, so new rules, dashboards and Alertmanager "
                        + "config never reach them.");
    }

    private static String updateBranch() throws IOException {
        String installer = Files.readString(INSTALLER, StandardCharsets.UTF_8);
        int start = installer.indexOf("        update)");
        int end = installer.indexOf("        *)", start);
        assertTrue(start >= 0 && end > start, "install.sh's monitoring command no longer has an update branch");
        return installer.substring(start, end);
    }
}

package com.webhook.platform.api.ops;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

// Only the API runs Flyway; a worker started first fails schema validation.
@Tag("ratchet")
class UpgradeOrderTest {

    private static final Path INSTALLER = Paths.get("..", "install.sh");

    @Test
    @DisplayName("the upgrade replaces the worker only after the new API is serving")
    void workerIsReplacedAfterTheApi() throws IOException {
        List<String> lines = upgradeBlock();

        int rollApi = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).trim().startsWith("roll_api")) {
                rollApi = i;
                break;
            }
        }
        assertTrue(rollApi >= 0, "install.sh's upgrade command no longer calls roll_api — has the API roll moved?");

        for (int i = 0; i < rollApi; i++) {
            String code = lines.get(i).trim();
            if (code.startsWith("#")) {
                continue;
            }
            assertTrue(!(code.contains("up_one") && code.contains("worker")),
                    "install.sh's upgrade brings the worker up before roll_api (\"" + code + "\"). Only the API "
                            + "runs migrations, so a new worker started first validates an unmigrated schema "
                            + "and crashes. Replace the worker after roll_api.");
        }

        boolean workerAfter = lines.subList(rollApi + 1, lines.size()).stream()
                .map(String::trim)
                .anyMatch(code -> !code.startsWith("#") && code.contains("up_one") && code.contains("worker"));
        assertTrue(workerAfter,
                "install.sh's upgrade never replaces the worker after roll_api, so an upgrade would leave the "
                        + "previous release's worker running beside the new API.");
    }

    private static List<String> upgradeBlock() throws IOException {
        String installer = Files.readString(INSTALLER, StandardCharsets.UTF_8);
        int upgrade = installer.indexOf("    upgrade)");
        int backup = installer.indexOf("    backup)");
        assertTrue(upgrade >= 0 && backup > upgrade,
                "install.sh's helper no longer has both an upgrade and a backup command in that order");
        return installer.substring(upgrade, backup).lines().toList();
    }
}

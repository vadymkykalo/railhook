package com.webhook.platform.api.tenancy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// TaskDecorator reaches only AsyncConfig's beans, so a hand-built pool must use TenantPropagatingTaskDecorator.wrap.
@Tag("ratchet")
class HandBuiltExecutorTenantPropagationTest {

    private static final Path SOURCE_ROOT = Paths.get("src/main/java");

    private static final Pattern BUILDS_A_POOL = Pattern.compile(
            "Executors\\s*\\.\\s*new\\w+\\s*\\("
                    + "|new\\s+(Scheduled)?ThreadPoolExecutor\\s*\\("
                    + "|new\\s+ForkJoinPool\\s*\\(");

    private static final String WRAPPED = "TenantPropagatingTaskDecorator.wrap(";

    // Empty, and meant to stay so: an entry must say which organization the pool's tasks run under.
    private static final Set<String> DOCUMENTED_EXEMPTIONS = new TreeSet<>(Set.of());

    @Test
    @DisplayName("every hand-built pool is wrapped for tenant propagation, or is a documented exemption")
    void handBuiltPoolsPropagateTheTenant() throws IOException {
        Set<String> builders = filesThatBuildAPool();

        assertTrue(builders.size() >= 2,
                "the scan found " + builders.size() + " files building a thread pool. Two are known "
                        + "(AuditLogAspect, WorkflowEngine), so a lower count means the scan is broken "
                        + "and this test is vacuous.");

        Set<String> unwrapped = new TreeSet<>();
        for (String file : builders) {
            if (!read(file).contains(WRAPPED)) {
                unwrapped.add(file);
            }
        }
        unwrapped.removeAll(DOCUMENTED_EXEMPTIONS);

        assertEquals(Set.of(), unwrapped,
                "These files build a thread pool that never reaches "
                        + "TenantPropagatingTaskDecorator.wrap(...). A task on such a pool starts with "
                        + "whatever scope the previous task left behind — no scope at all on a fresh "
                        + "thread — so it either fails on its first query or stamps the wrong "
                        + "organization on a row. Wrap the pool, or add it to "
                        + "DOCUMENTED_EXEMPTIONS with a reason.");
    }

    @Test
    @DisplayName("the exemption list has no stale entries")
    void exemptionsAreAllStillUnwrappedPoolBuilders() throws IOException {
        Set<String> builders = filesThatBuildAPool();

        Set<String> stale = new TreeSet<>();
        for (String exempt : DOCUMENTED_EXEMPTIONS) {
            if (!builders.contains(exempt) || read(exempt).contains(WRAPPED)) {
                stale.add(exempt);
            }
        }

        assertEquals(Set.of(), stale,
                "These entries are no longer needed — the file was wrapped, renamed or no longer "
                        + "builds a pool. Drop them so the list keeps meaning something.");
    }

    private Set<String> filesThatBuildAPool() throws IOException {
        try (Stream<Path> files = Files.walk(SOURCE_ROOT)) {
            Set<String> found = new TreeSet<>();
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (BUILDS_A_POOL.matcher(Files.readString(file, StandardCharsets.UTF_8)).find()) {
                    found.add(SOURCE_ROOT.relativize(file).toString());
                }
            }
            return found;
        }
    }

    private String read(String relativePath) throws IOException {
        return Files.readString(SOURCE_ROOT.resolve(relativePath), StandardCharsets.UTF_8);
    }
}

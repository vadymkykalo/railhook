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

/**
 * Ratchet over thread pools this module builds itself.
 *
 * <p>Spring's {@code TaskDecorator} hook reaches only the executors declared as beans in
 * {@code AsyncConfig}, whose javadoc says "every executor here gets it" — true of that file and
 * false of the codebase. Two pools were built by hand outside it, and each had grown its own
 * answer to the resulting gap: {@code AuditLogAspect} wrapped every task body in
 * {@code TenantContext.runAs}, and {@code WorkflowEngine} re-implemented
 * {@link TenantPropagatingTaskDecorator} inline — already missing its {@code captured == null}
 * pass-through, so an unscoped submission wrote {@code null} into the ThreadLocal rather than
 * leaving the worker thread's own scope alone.
 *
 * <p>Neither divergence failed anything. A task that starts with no tenant either throws
 * {@code TenantNotResolvedException} on its first session — which the audit writer's catch-all
 * swallowed into a log line — or, worse, runs under a scope left behind by the previous task on
 * that thread and stamps the wrong organization on a row.
 *
 * <p>So: a file that constructs a pool must also hand it to
 * {@link TenantPropagatingTaskDecorator#wrap}. The check is at file level on purpose — it asks
 * whether the author thought about propagation at all, not whether a particular expression is
 * shaped a particular way, and the reason is that the two ways of getting this wrong so far were
 * both "wrote something bespoke", not "wrote the wrapper slightly wrong".
 *
 * <p>Not covered, deliberately: a bare {@code @Async} with no qualifier, which lands on Spring
 * Boot's auto-configured {@code applicationTaskExecutor} rather than one of {@code AsyncConfig}'s.
 * There is one ({@code AlertNotificationService.dispatch}) and it is safe today because it touches
 * no {@code @TenantId} entity — it formats a message and makes an HTTP call. Widening this test to
 * cover it means either giving that method a decorated executor or exempting it, which is a
 * decision, not a ratchet.
 *
 * <p>Deliberately a plain {@code *Test}: it reads source files off disk and needs no Spring
 * context and no container, so it must run in the no-Docker unit job (see
 * {@code scripts/check-test-routing.sh}).
 */
@Tag("ratchet")
class HandBuiltExecutorTenantPropagationTest {

    private static final Path SOURCE_ROOT = Paths.get("src/main/java");

    /** {@code Executors.newFixedThreadPool(…)}, {@code new ThreadPoolExecutor(…)}, and friends. */
    private static final Pattern BUILDS_A_POOL = Pattern.compile(
            "Executors\\s*\\.\\s*new\\w+\\s*\\("
                    + "|new\\s+(Scheduled)?ThreadPoolExecutor\\s*\\("
                    + "|new\\s+ForkJoinPool\\s*\\(");

    private static final String WRAPPED = "TenantPropagatingTaskDecorator.wrap(";

    /**
     * Files that build a pool and deliberately do not wrap it, with the reason each is acceptable.
     * Format is the path relative to {@code src/main/java}.
     *
     * <p>Empty, and meant to stay that way. Adding an entry is a tenancy decision: say which
     * organization the pool's tasks run under instead, and why the wrapper is wrong for it.
     */
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

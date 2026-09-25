package com.webhook.platform.api.domain.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// A native query supplies its own SQL, so @TenantId confines nothing; each names organization_id or is listed.
@Tag("ratchet")
class NativeQueryTenantPredicateTest {

    private static final String PACKAGE = "com.webhook.platform.api.domain.repository";
    private static final Path SOURCE_DIR = Paths.get("src/main/java/com/webhook/platform/api/domain/repository");

    // Each entry asserts the method only runs under @SystemTenant; getting that wrong is a cross-tenant read.
    private static final Set<String> SYSTEM_PATHS = new TreeSet<>(Set.of(
            // Tester tables belong to no organization; called only under @SystemTenant.
            "PublicBinRequestRepository.trimToNewest",
            // Sweeps every organization for sequences an ingest crash never backfilled.
            "DeliveryRepository.findOrderedDeliveriesMissingASequence",
            // Retention deletes by age across the whole table.
            "DeliveryAttemptRepository.deleteOldAttempts",
            "DeliveryAttemptRepository.deleteOldSuccessfulAttempts",
            "DeliveryAttemptRepository.deleteExcessAttemptsPerDelivery",
            "IncomingEventRepository.deleteOldIncomingEvents",
            "EventRepository.deleteOldEvents",
            "OutboxMessageRepository.deleteOldPublishedMessages",
            // Resolved alert history for every organization at once.
            "AlertEventRepository.deleteResolvedBefore",

            // Table-size estimates are properties of the table, not of an organization.
            "DeliveryAttemptRepository.countAllAttempts",
            "DeliveryAttemptRepository.estimatedRowCount",
            "IncomingEventRepository.estimatedRowCount",
            "EventRepository.estimatedRowCount",
            "EventRepository.estimatedDeliveryRowCount",

            // The outbox claims and settles every organization's messages in one batch.
            "OutboxMessageRepository.findOldestPendingCreatedAt",
            "OutboxMessageRepository.findPendingBatchForUpdate",
            "OutboxMessageRepository.findFailedMessagesForRetry",
            "OutboxMessageRepository.batchMarkPublished",
            "OutboxMessageRepository.batchMarkFailed",
            "OutboxMessageRepository.promoteExhaustedToDead",
            "OutboxMessageRepository.recoverStuckSendingMessages",
            "OutboxMessageRepository.deadLetterStuckSendingMessages",
            "WorkflowTriggerOutboxRepository.claimBatch",

            // Rebuilds the high-water mark for every endpoint on the instance.
            "DeliveryRepository.findMaxSequenceNumberPerEndpointSince",

            // Unreferenced today; cross-tenant in shape, like the outbox's batch settlement.
            "OutboxMessageRepository.batchMarkDead"
    ));

    @Test
    @DisplayName("every native query carries organization_id, or is a documented system path")
    void nativeQueriesAreConfinedOrDocumented() throws Exception {
        Set<String> all = new TreeSet<>();
        Set<String> unconfined = new TreeSet<>();

        for (Class<?> repository : repositoryInterfaces()) {
            for (Method method : repository.getDeclaredMethods()) {
                Query query = method.getAnnotation(Query.class);
                if (query == null || !query.nativeQuery()) {
                    continue;
                }
                String id = repository.getSimpleName() + "." + method.getName();
                all.add(id);
                if (!query.value().toLowerCase(Locale.ROOT).contains("organization_id")) {
                    unconfined.add(id);
                }
            }
        }

        assertTrue(all.size() > 20,
                "the scan found only " + all.size() + " native queries — the repository scan is "
                        + "probably broken, which would make this test vacuous");

        Set<String> unexpected = new TreeSet<>(unconfined);
        unexpected.removeAll(SYSTEM_PATHS);
        assertEquals(Set.of(), unexpected,
                "These native queries mention no organization_id. Hibernate's @TenantId "
                        + "discriminator does not reach native SQL, so a read returns every "
                        + "organization's rows and a write stamps none. Add the predicate "
                        + "or the column, or — if the method genuinely runs only under the system "
                        + "tenant — add it to SYSTEM_PATHS with the reason.");
    }

    @Test
    @DisplayName("a repository that reaches past Hibernate writes its own tenant predicate")
    void jdbcTemplateRepositoriesConfineThemselves() throws IOException {
        // A class holding a JdbcTemplate bypasses @TenantId, so it must confine by hand, visibly.
        for (Path file : repositorySourceFiles()) {
            String source = Files.readString(file);
            if (!source.contains("JdbcTemplate")) {
                continue;
            }
            assertTrue(source.contains("TenantContext"),
                    file.getFileName() + " runs SQL outside Hibernate, so @TenantId does not "
                            + "reach it. Confine every query in it with organization_id taken "
                            + "from TenantContext.require() — not from a parameter, which is a "
                            + "second mechanism that will eventually disagree with the first.");
        }
    }

    @Test
    @DisplayName("the system-path list has no stale entries")
    void systemPathsAreAllStillUnconfinedNativeQueries() throws Exception {
        Set<String> unconfined = new TreeSet<>();
        for (Class<?> repository : repositoryInterfaces()) {
            for (Method method : repository.getDeclaredMethods()) {
                Query query = method.getAnnotation(Query.class);
                if (query != null && query.nativeQuery()
                        && !query.value().toLowerCase(Locale.ROOT).contains("organization_id")) {
                    unconfined.add(repository.getSimpleName() + "." + method.getName());
                }
            }
        }

        Set<String> stale = new TreeSet<>(SYSTEM_PATHS);
        stale.removeAll(unconfined);
        assertEquals(Set.of(), stale,
                "These entries are no longer needed — the query gained its predicate, was renamed "
                        + "or was removed. Drop them so the list keeps meaning something.");
    }

    private List<Path> repositorySourceFiles() throws IOException {
        try (Stream<Path> files = Files.list(SOURCE_DIR)) {
            return files.filter(p -> p.getFileName().toString().endsWith("Repository.java"))
                    .sorted()
                    .toList();
        }
    }

    private List<Class<?>> repositoryInterfaces() throws IOException {
        try (Stream<Path> files = Files.list(SOURCE_DIR)) {
            return files
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith("Repository.java"))
                    .map(name -> name.substring(0, name.length() - ".java".length()))
                    .sorted()
                    .<Class<?>>map(simpleName -> {
                        try {
                            return Class.forName(PACKAGE + "." + simpleName);
                        } catch (ClassNotFoundException e) {
                            throw new IllegalStateException("Found " + simpleName + ".java but could not load it", e);
                        }
                    })
                    .toList();
        }
    }
}

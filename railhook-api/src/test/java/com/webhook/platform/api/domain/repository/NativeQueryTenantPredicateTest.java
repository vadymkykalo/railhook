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

/**
 * Ratchet over the one hole in the structural tenancy.
 *
 * <p>Everything else in this package is confined for free: the entities carry {@code @TenantId},
 * so Hibernate adds {@code organization_id = <current tenant>} when it builds the SQL, and a new
 * derived method or JPQL {@code @Query} inherits that without its author doing anything. A
 * {@code nativeQuery = true} method supplies its own SQL, so it inherits nothing — the rule is
 * stated in this package's {@code package-info} and, until this test, checked by nobody.
 *
 * <p>It has already been violated once in each direction of badness. {@code usage_daily} gained a
 * NOT NULL {@code organization_id} in V056 while its INSERT stayed native, so the nightly
 * aggregation hit a constraint violation at 00:05 every night — swallowed by the per-project catch
 * in {@code aggregateYesterday}, so nothing paged and the table simply stopped filling. The other
 * direction is worse and quieter: a native SELECT with no predicate returns other organizations'
 * rows, and looks exactly like a working query.
 *
 * <p>So every native query is one of two things, and has to say which:
 *
 * <ul>
 *   <li><b>reachable from a request</b> — carries {@code organization_id} in its SQL, whether as a
 *       predicate on a read or a column on a write; or</li>
 *   <li><b>a system path that is meant to cross tenants</b> — retention deletes, outbox claims,
 *       table-size estimates. Listed in {@link #SYSTEM_PATHS} with the reason.</li>
 * </ul>
 *
 * <p>The check is on the SQL string rather than on runtime behaviour on purpose: {@code @Query} is
 * {@code RUNTIME}-retained, so this is plain reflection and runs in the no-Docker unit job. A
 * runtime check would need a container and would still only cover the paths some test happens to
 * exercise, which is the opposite of what a ratchet is for.
 *
 * <p>Deliberately a plain {@code *Test} — see {@code scripts/check-test-routing.sh}.
 */
@Tag("ratchet")
class NativeQueryTenantPredicateTest {

    private static final String PACKAGE = "com.webhook.platform.api.domain.repository";
    private static final Path SOURCE_DIR = Paths.get("src/main/java/com/webhook/platform/api/domain/repository");

    /**
     * Native queries that deliberately cross organizations, with the reason each has to.
     *
     * <p>Adding an entry is a tenancy decision, not a formality: it asserts that the method is
     * unreachable from a request thread and only ever runs under {@code @SystemTenant} or
     * {@code TenantContext.runAsSystem}. Getting that wrong is a cross-tenant read.
     */
    private static final Set<String> SYSTEM_PATHS = new TreeSet<>(Set.of(
            // Retention: DataRetentionService's five @SystemTenant schedulers delete by age
            // across the whole table. A tenant predicate would leave every other organization's
            // rows behind and the table would grow without bound.
            "DeliveryAttemptRepository.deleteOldAttempts",
            "DeliveryAttemptRepository.deleteOldSuccessfulAttempts",
            "DeliveryAttemptRepository.deleteExcessAttemptsPerDelivery",
            "IncomingEventRepository.deleteOldIncomingEvents",
            "EventRepository.deleteOldEvents",
            "OutboxMessageRepository.deleteOldPublishedMessages",

            // Table-size estimates driving the retention thresholds: pg_stat_user_tables and
            // COUNT(*) are properties of the table, not of an organization.
            "DeliveryAttemptRepository.countAllAttempts",
            "DeliveryAttemptRepository.estimatedRowCount",
            "IncomingEventRepository.estimatedRowCount",
            "EventRepository.estimatedRowCount",
            "EventRepository.estimatedDeliveryRowCount",

            // The outbox is the one place that is deliberately cross-tenant by design:
            // OutboxPublisherService polls, claims and settles every organization's messages in
            // one batch under @SystemTenant, with a per-project fairness cap inside the SQL.
            "OutboxMessageRepository.findOldestPendingCreatedAt",
            "OutboxMessageRepository.findPendingBatchForUpdate",
            "OutboxMessageRepository.findFailedMessagesForRetry",
            "OutboxMessageRepository.batchMarkPublished",
            "OutboxMessageRepository.batchMarkFailed",
            "OutboxMessageRepository.promoteExhaustedToDead",
            "OutboxMessageRepository.recoverStuckSendingMessages",
            "WorkflowTriggerOutboxRepository.claimBatch",

            // Sequence reconciliation: SequenceReconciliationService (@SystemTenant) rebuilds the
            // per-endpoint high-water mark for every endpoint on the instance.
            "DeliveryRepository.findMaxSequenceNumberPerEndpointSince",

            // Unreferenced today, and listed rather than deleted because deleting a repository
            // method is not this branch's business. Both are cross-tenant in shape — a retention
            // delete by age, and the DEAD counterpart of the outbox's batch settlement — so
            // whoever wires them up inherits the right answer rather than a red build.
            "AlertEventRepository.deleteOlderThan",
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

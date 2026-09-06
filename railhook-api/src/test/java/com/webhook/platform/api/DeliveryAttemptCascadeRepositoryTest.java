package com.webhook.platform.api;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * delivery_attempts must cascade from deliveries — on every partition.
 *
 * <p>V001 declared {@code delivery_id ... REFERENCES deliveries(id) ON DELETE CASCADE}. V052
 * rebuilt the table as a partitioned parent, faithfully re-creating every column and every
 * index, and dropped the foreign key without saying so — its own comment enumerates what it
 * changed and does not mention it. The constraint survived only on delivery_attempts_legacy,
 * the attached pre-cutover partition, so rows written before the cutover still cascaded and
 * rows written after it did not.</p>
 *
 * <p>{@code DlqService.purgeAllDlq} therefore deleted deliveries and left their attempts
 * standing — request and response bodies included — with nothing able to reach them again:
 * {@code delivery_id} is the only way in. Dropping a monthly partition eventually reclaimed
 * the space, which is why it went unnoticed, but a purge did not mean what it said.</p>
 *
 * <p>This asserts the schema, not Postgres's cascade semantics: the regression to guard
 * against is a migration rebuilding this table and forgetting the constraint again, which is
 * precisely what happened. Behaviour was confirmed separately against PostgreSQL 16 —
 * including that a partition created later inherits the constraint from the parent, so
 * PartitionMaintenanceService needs no change.</p>
 */
public class DeliveryAttemptCascadeRepositoryTest extends AbstractIntegrationTest {

    @PersistenceContext
    private EntityManager entityManager;

    // A TransactionTemplate rather than @Transactional on the method: the test-context
    // transaction would open before AbstractIntegrationTest's @BeforeEach enters the system
    // tenant scope, and the tenant resolver refuses to build an EntityManager without one.
    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void everyPartitionCascadesFromDeliveries() {
        @SuppressWarnings("unchecked")
        List<Object[]> constraints = (List<Object[]>) transactionTemplate.execute(tx ->
                entityManager.createNativeQuery("""
                        SELECT c.conrelid::regclass::text AS on_table,
                               c.confdeltype              AS on_delete
                          FROM pg_constraint c
                          JOIN pg_class ref ON ref.oid = c.confrelid
                         WHERE c.contype = 'f'
                           AND ref.relname = 'deliveries'
                           AND c.conrelid::regclass::text LIKE 'delivery_attempts%'
                        """).getResultList());

        List<String> tables = constraints.stream().map(row -> (String) row[0]).toList();

        assertTrue(tables.contains("delivery_attempts"),
                "the foreign key belongs on the partitioned parent, so every partition — "
                        + "including ones PartitionMaintenanceService creates later — inherits it");

        // The parent's constraint is mirrored onto each partition. Any partition without one
        // is a partition whose rows outlive the delivery they belong to.
        @SuppressWarnings("unchecked")
        List<String> partitions = (List<String>) transactionTemplate.execute(tx ->
                entityManager.createNativeQuery("""
                        SELECT c.relname
                          FROM pg_class c
                          JOIN pg_inherits i ON i.inhrelid = c.oid
                          JOIN pg_class p ON p.oid = i.inhparent
                         WHERE p.relname = 'delivery_attempts'
                        """).getResultList());

        assertFalse(partitions.isEmpty(), "expected at least the attached legacy partition");
        for (String partition : partitions) {
            assertTrue(tables.contains(partition),
                    partition + " has no foreign key to deliveries: rows written into it "
                            + "survive the delivery they belong to, holding request and "
                            + "response bodies nothing can reach");
        }

        for (Object[] row : constraints) {
            // 'c' = ON DELETE CASCADE. 'a' (no action) would leave the purge failing instead
            // of cleaning up, which is a different bug, not a fix.
            assertEquals('c', ((Character) row[1]).charValue(),
                    row[0] + " must cascade, as V001 declared");
        }
    }
}

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

// V052 once dropped this FK silently; guards a migration forgetting it again.
public class DeliveryAttemptCascadeRepositoryTest extends AbstractIntegrationTest {

    @PersistenceContext
    private EntityManager entityManager;

    // TransactionTemplate: a test transaction would open before the tenant scope is entered.
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
            // 'c' = ON DELETE CASCADE.
            assertEquals('c', ((Character) row[1]).charValue(),
                    row[0] + " must cascade, as V001 declared");
        }
    }
}

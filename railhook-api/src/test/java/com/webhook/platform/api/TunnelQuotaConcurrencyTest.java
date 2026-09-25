package com.webhook.platform.api;

import com.webhook.platform.api.domain.enums.TunnelStatus;
import com.webhook.platform.api.domain.repository.TunnelSessionRepository;
import com.webhook.platform.api.exception.QuotaExceededException;
import com.webhook.platform.api.service.TunnelService;
import com.webhook.platform.api.tenancy.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

@TestPropertySource(properties = "billing.enabled=true")
class TunnelQuotaConcurrencyTest extends AbstractIntegrationTest {

    private static final int ROUNDS = 5;
    private static final int OPENERS = 4;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private TunnelService tunnelService;

    @Autowired
    private TunnelSessionRepository tunnelSessionRepository;

    @Test
    void concurrentOpensOnAOneTunnelPlanLeaveExactlyOneTunnel() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(OPENERS);
        try {
            for (int round = 0; round < ROUNDS; round++) {
                UUID organizationId = seedFreeOrganization();
                UUID userId = UUID.randomUUID();
                CyclicBarrier together = new CyclicBarrier(OPENERS);

                List<Future<Boolean>> opens = new ArrayList<>();
                for (int i = 0; i < OPENERS; i++) {
                    opens.add(pool.submit(() -> {
                        together.await(10, TimeUnit.SECONDS);
                        try {
                            TenantContext.callAs(organizationId,
                                    () -> tunnelService.createSession(userId, null, 3000, "cli"));
                            return true;
                        } catch (QuotaExceededException refused) {
                            return false;
                        }
                    }));
                }
                int opened = 0;
                for (Future<Boolean> open : opens) {
                    if (open.get(30, TimeUnit.SECONDS)) {
                        opened++;
                    }
                }

                long active = TenantContext.callAs(organizationId, () ->
                        tunnelSessionRepository.countByOrganizationIdAndStatus(organizationId, TunnelStatus.ACTIVE));
                assertEquals(1, opened, "round " + round + ": exactly one open may succeed");
                assertEquals(1L, active, "round " + round + ": the Free plan allows one active tunnel");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private UUID seedFreeOrganization() {
        UUID organizationId = UUID.randomUUID();
        transactionTemplate.executeWithoutResult(tx -> entityManager.createNativeQuery("""
                        INSERT INTO organizations (id, name, plan_id)
                        VALUES (:id, 'tunnel-quota-race', (SELECT id FROM plans WHERE name = 'free'))
                        """)
                .setParameter("id", organizationId).executeUpdate());
        return organizationId;
    }
}

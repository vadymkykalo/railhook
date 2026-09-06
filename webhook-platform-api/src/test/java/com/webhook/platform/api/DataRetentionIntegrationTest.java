package com.webhook.platform.api;

import org.junit.jupiter.api.AfterEach;
import com.webhook.platform.api.tenancy.TenantContext;
import com.webhook.platform.api.domain.entity.DeliveryAttempt;
import com.webhook.platform.api.domain.repository.DeliveryAttemptRepository;
import com.webhook.platform.api.domain.repository.DeliveryRepository;
import com.webhook.platform.api.service.DataRetentionService;
import com.webhook.platform.api.service.RedisTunnelCoordinator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

import com.webhook.platform.api.service.AuthRateLimiterService;
import com.webhook.platform.api.service.OutboxPublisherService;
import com.webhook.platform.api.service.RedisRateLimiterService;
import com.webhook.platform.api.service.SequenceGeneratorService;
import com.webhook.platform.api.service.TestEndpointCleanupService;
import com.webhook.platform.api.service.TokenBlacklistService;
import org.redisson.api.RedissonClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.annotation.DirtiesContext;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration,org.redisson.spring.starter.RedissonAutoConfigurationV2"
    }
)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public class DataRetentionIntegrationTest {

    /**
     * Fixture tenant for rows inserted straight through JDBC.
     *
     * <p>These fixtures bypass the entity mapping (and the FK checks, via
     * {@code session_replication_role = replica}) that would normally stamp
     * {@code organization_id}, so they name one themselves. The value only has to be non-null and
     * consistent — nothing here asserts on tenant confinement.
     */
    private static final UUID FIXTURE_ORG = UUID.randomUUID();

    @MockitoBean
    private RedissonClient redissonClient;

    @MockitoBean
    private SequenceGeneratorService sequenceGeneratorService;

    @MockitoBean
    private RedisRateLimiterService redisRateLimiterService;

    @MockitoBean
    private OutboxPublisherService outboxPublisherService;

    @MockitoBean
    private TestEndpointCleanupService testEndpointCleanupService;

    @MockitoBean
    private AuthRateLimiterService authRateLimiterService;

    @MockitoBean
    private TokenBlacklistService tokenBlacklistService;

    @MockitoBean
    private RedisTunnelCoordinator redisTunnelCoordinator;

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("testdb")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("data-retention.max-attempts-per-delivery", () -> "10");
        registry.add("data-retention.delivery-attempts-retention-days", () -> "90");
        registry.add("data-retention.batch-size", () -> "1000");
        registry.add("webhook.encryption-key", () -> "test_encryption_key_32_chars_pad_extra");
        registry.add("webhook.encryption-keys", () -> "");
        registry.add("webhook.encryption-key-active-version", () -> "0");
        registry.add("webhook.encryption-salt", () -> "test_salt_for_integration_tests");
        registry.add("jwt.secret", () -> "test_jwt_secret_key_minimum_32_chars_required_here");
        registry.add("jwt.expiration-ms", () -> "3600000");
    }

    @Autowired
    private DataRetentionService dataRetentionService;

    @Autowired
    private DeliveryAttemptRepository deliveryAttemptRepository;

    @Autowired
    private DeliveryRepository deliveryRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;
    
    @Autowired
    private TransactionTemplate transactionTemplate;


    /**
     * These two build their own {@code @SpringBootTest} rather than extending
     * {@code AbstractIntegrationTest}, so they enter the system tenant scope themselves. They read
     * and delete rows across organizations directly, which is exactly what that scope means.
     */
    @BeforeEach
    void enterSystemTenantScope() {
        TenantContext.set(TenantContext.SYSTEM);
    }

    @AfterEach
    void leaveSystemTenantScope() {
        TenantContext.clear();
    }

    @BeforeEach
    void cleanup() {
        jdbcTemplate.execute("SET session_replication_role = replica");
        deliveryAttemptRepository.deleteAll();
        deliveryRepository.deleteAll();
        jdbcTemplate.execute("SET session_replication_role = DEFAULT");
    }

    @Test
    void testPerDeliveryAttemptLimitEnforcement() {
        UUID deliveryId1 = createDelivery();
        UUID deliveryId2 = createDelivery();

        for (int i = 1; i <= 15; i++) {
            createAttempt(deliveryId1, i, Instant.now());
        }

        for (int i = 1; i <= 5; i++) {
            createAttempt(deliveryId2, i, Instant.now());
        }

        long beforeCount = deliveryAttemptRepository.count();
        assertEquals(20, beforeCount);

        transactionTemplate.execute(status -> {
            dataRetentionService.enforcePerDeliveryAttemptLimits();
            return null;
        });

        List<DeliveryAttempt> delivery1Attempts = deliveryAttemptRepository
                .findByDeliveryIdOrderByAttemptNumberAsc(deliveryId1);
        List<DeliveryAttempt> delivery2Attempts = deliveryAttemptRepository
                .findByDeliveryIdOrderByAttemptNumberAsc(deliveryId2);

        assertEquals(10, delivery1Attempts.size());
        assertEquals(5, delivery2Attempts.size());

        assertEquals(6, delivery1Attempts.get(0).getAttemptNumber());
        assertEquals(15, delivery1Attempts.get(9).getAttemptNumber());
    }

    // testAgeBasedCleanup (dataRetentionService.cleanupOldDeliveryAttempts()) was removed:
    // delivery_attempts is now partitioned monthly (V052) and the global
    // age-based cutoff this test exercised is now enforced by
    // PartitionMaintenanceService.dropExpiredPartitions() (O(1) DROP TABLE on whole
    // expired partitions) instead of a row-level DELETE. See
    // PartitionMaintenanceServiceIntegrationTest for coverage of that behavior.
    // deliveryAttemptRepository.deleteOldAttempts() itself is untouched and still
    // covered by testBatchSizeRespected() below.

    @Test
    void testBatchSizeRespected() {
        UUID deliveryId = createDelivery();
        Instant old = Instant.now().minus(100, ChronoUnit.DAYS);

        for (int i = 1; i <= 1500; i++) {
            createAttemptWithTimestamp(deliveryId, i, old);
        }

        long beforeCount = deliveryAttemptRepository.count();
        assertEquals(1500, beforeCount);

        Instant cutoff = Instant.now().minus(90, ChronoUnit.DAYS);
        Integer deleted = transactionTemplate.execute(status -> 
            deliveryAttemptRepository.deleteOldAttempts(cutoff, 1000)
        );
        
        assertNotNull(deleted);
        assertTrue(deleted > 0, "Should delete some attempts");
        assertTrue(deleted <= 1000, "Should respect batch limit");
        
        long afterFirstRun = deliveryAttemptRepository.count();
        assertEquals(1500 - deleted, afterFirstRun);
    }

    @Test
    void testNoDeleteWhenUnderLimit() {
        UUID deliveryId = createDelivery();

        for (int i = 1; i <= 8; i++) {
            createAttempt(deliveryId, i, Instant.now());
        }

        long beforeCount = deliveryAttemptRepository.count();
        assertEquals(8, beforeCount);

        transactionTemplate.execute(status -> {
            dataRetentionService.enforcePerDeliveryAttemptLimits();
            return null;
        });

        long afterCount = deliveryAttemptRepository.count();
        assertEquals(8, afterCount);

        List<DeliveryAttempt> attempts = deliveryAttemptRepository
                .findByDeliveryIdOrderByAttemptNumberAsc(deliveryId);
        assertEquals(8, attempts.size());
    }

    private UUID createDelivery() {
        UUID deliveryId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID endpointId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        
        jdbcTemplate.execute("SET session_replication_role = replica");
        jdbcTemplate.update(
            "INSERT INTO deliveries (id, event_id, endpoint_id, subscription_id, organization_id, status, attempt_count, max_attempts, ordering_enabled, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            deliveryId, eventId, endpointId, subscriptionId, FIXTURE_ORG, "PENDING", 0, 5, false, now, now
        );
        jdbcTemplate.execute("SET session_replication_role = DEFAULT");
        
        return deliveryId;
    }

    private void createAttempt(UUID deliveryId, int attemptNumber, Instant createdAt) {
        jdbcTemplate.execute("SET session_replication_role = replica");
        jdbcTemplate.update(
            "INSERT INTO delivery_attempts (id, delivery_id, organization_id, attempt_number, http_status_code, duration_ms, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
            UUID.randomUUID(), deliveryId, FIXTURE_ORG, attemptNumber, 200, 100, Timestamp.from(Instant.now())
        );
        jdbcTemplate.execute("SET session_replication_role = DEFAULT");
    }

    private void createAttemptWithTimestamp(UUID deliveryId, int attemptNumber, Instant createdAt) {
        jdbcTemplate.execute("SET session_replication_role = replica");
        jdbcTemplate.update(
                "INSERT INTO delivery_attempts (id, delivery_id, organization_id, attempt_number, http_status_code, duration_ms, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), deliveryId, FIXTURE_ORG, attemptNumber, 200, 100, Timestamp.from(createdAt)
        );
        jdbcTemplate.execute("SET session_replication_role = DEFAULT");
    }
}

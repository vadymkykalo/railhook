package com.webhook.platform.api;

import com.webhook.platform.api.service.AuthRateLimiterService;
import com.webhook.platform.api.service.OutboxPublisherService;
import com.webhook.platform.api.service.RedisTunnelCoordinator;
import com.webhook.platform.api.service.RedisRateLimiterService;
import com.webhook.platform.api.service.SequenceGeneratorService;
import com.webhook.platform.api.service.SequenceReconciliationService;
import com.webhook.platform.api.service.TestEndpointCleanupService;
import com.webhook.platform.api.service.TokenBlacklistService;
import com.webhook.platform.api.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.redisson.api.RedissonClient;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.autoconfigure.exclude=org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration,org.redisson.spring.starter.RedissonAutoConfigurationV2"
        }
)
@Testcontainers
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class AbstractIntegrationTest {

    @MockitoBean
    protected RedissonClient redissonClient;

    @MockitoBean
    protected SequenceGeneratorService sequenceGeneratorService;

    @MockitoBean
    protected SequenceReconciliationService sequenceReconciliationService;

    @MockitoBean
    protected RedisRateLimiterService redisRateLimiterService;

    @MockitoBean
    protected OutboxPublisherService outboxPublisherService;

    @MockitoBean
    protected TestEndpointCleanupService testEndpointCleanupService;

    @MockitoBean
    protected AuthRateLimiterService authRateLimiterService;

    @MockitoBean
    protected TokenBlacklistService tokenBlacklistService;

    @MockitoBean
    protected RedisTunnelCoordinator redisTunnelCoordinator;

    /**
     * Integration tests reach for repositories directly — seeding fixtures, asserting on rows,
     * often for several organizations in one test. That is system work, and system
     * work say so: without a scope the first repository call fails with
     * TenantNotResolvedException.
     *
     * <p>Entered here rather than in each test so the default matches what these tests are: an
     * out-of-band view of the database. A test that wants to prove confinement enters a real
     * tenant itself with {@code TenantContext.runAs(...)} — see {@code CrossTenantIsolationTest}.
     * Requests made through MockMvc are unaffected: they go through TenantContextFilter and get
     * the caller's own scope.
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
    void setupMocks() {
        when(authRateLimiterService.allowLogin(anyString(), any())).thenReturn(true);
        when(authRateLimiterService.allowRegister(anyString())).thenReturn(true);
        // allowTokenAction backs /auth/forgot-password's IP+email check and
        // /auth/reset-password's IP+token check — without stubbing it, an unstubbed
        // Mockito boolean mock defaults to false and every reset-password call gets a
        // spurious 429 (pre-existing gap here since allowTokenAction was added; not
        // related to password-reset hashing, just needed so PasswordResetIntegrationTest can run at all).
        when(authRateLimiterService.allowTokenAction(anyString(), any())).thenReturn(true);
        when(tokenBlacklistService.isBlacklisted(any())).thenReturn(false);
        when(tokenBlacklistService.isTokenRevokedByEpoch(any(), any())).thenReturn(false);
    }

    @Container
    protected static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("webhook_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("webhook.encryption-key", () -> "test_encryption_key_32_chars_pad_extra");
        registry.add("webhook.encryption-keys", () -> "");
        registry.add("webhook.encryption-key-active-version", () -> "0");
        registry.add("webhook.encryption-salt", () -> "test_salt_for_integration_tests");
        registry.add("jwt.secret", () -> "test_jwt_secret_key_minimum_32_chars_required_here");
        registry.add("jwt.expiration-ms", () -> "3600000");
        registry.add("platform.admin.token", () -> PLATFORM_ADMIN_TEST_TOKEN);
    }

    /**
     * Shared platform-admin operator credential for integration tests.
     * Any test wanting to authenticate as the platform admin sends this value in the
     * {@code X-Platform-Admin-Token} header.
     */
    protected static final String PLATFORM_ADMIN_TEST_TOKEN = "test_platform_admin_operator_token";
}

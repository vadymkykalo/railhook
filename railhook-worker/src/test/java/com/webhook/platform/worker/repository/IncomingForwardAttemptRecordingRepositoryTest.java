package com.webhook.platform.worker.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.webhook.platform.common.dto.IncomingForwardMessage;
import com.webhook.platform.common.enums.ForwardAttemptStatus;
import com.webhook.platform.common.enums.IncomingAuthType;
import com.webhook.platform.common.retry.RetryLadderDefaults;
import com.webhook.platform.worker.attempt.AttemptMetrics;
import com.webhook.platform.worker.attempt.AttemptRunner;
import com.webhook.platform.worker.attempt.IncomingAttemptStore;
import com.webhook.platform.worker.attempt.ProjectStatusLookup;
import com.webhook.platform.worker.domain.entity.IncomingDestination;
import com.webhook.platform.worker.domain.entity.IncomingEvent;
import com.webhook.platform.worker.domain.entity.IncomingForwardAttempt;
import com.webhook.platform.worker.domain.repository.IncomingForwardAttemptRepository;
import com.webhook.platform.worker.service.CircuitBreakerService;
import com.webhook.platform.worker.service.ProjectRateLimiterService;
import com.webhook.platform.worker.service.RedisConcurrencyControlService;
import com.webhook.platform.worker.service.RedisRateLimiterService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.reactive.function.client.WebClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * What a Forward's attempt row says was sent, written by the real store into a real Postgres after
 * a real HTTP request — the whole path the dashboard's "Request body" is read back from.
 *
 * <p>The store and the Runner each have unit tests that pass a fake to the other, so neither could
 * notice a body that was sent but never landed on the row. Production reported exactly that: an
 * incoming forward attempt with an empty request body.
 */
@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration,"
                + "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration,"
                + "org.springframework.boot.data.redis.autoconfigure.DataRedisRepositoriesAutoConfiguration"
})
class IncomingForwardAttemptRecordingRepositoryTest {

    private static final UUID FIXTURE_ORG = UUID.randomUUID();

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("webhook_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }

    @Autowired
    private IncomingForwardAttemptRepository attemptRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private HttpServer server;
    private String url;
    private final AtomicReference<byte[]> received = new AtomicReference<>();
    private AttemptRunner runner;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            received.set(exchange.getRequestBody().readAllBytes());
            byte[] ok = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, ok.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(ok);
            }
        });
        server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";

        ProjectRateLimiterService tenantRateLimiter = mock(ProjectRateLimiterService.class);
        RedisRateLimiterService targetRateLimiter = mock(RedisRateLimiterService.class);
        RedisConcurrencyControlService concurrency = mock(RedisConcurrencyControlService.class);
        CircuitBreakerService circuitBreaker = mock(CircuitBreakerService.class);
        lenient().when(tenantRateLimiter.tryAcquire(any(UUID.class))).thenReturn(true);
        lenient().when(targetRateLimiter.tryAcquire(any(UUID.class), anyInt())).thenReturn(true);
        lenient().when(concurrency.tryAcquireForTenant(any(UUID.class))).thenReturn(true);
        lenient().when(concurrency.tryAcquireForTarget(any(UUID.class))).thenReturn(true);
        lenient().when(circuitBreaker.isCallPermitted(any(UUID.class))).thenReturn(true);
        // allowPrivateIps: the receiver is on loopback.
        runner = new AttemptRunner(tenantRateLimiter, targetRateLimiter, concurrency, circuitBreaker,
                new ObjectMapper(), true, List.of());
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void anUntransformedForwardRecordsTheBodyItSent() {
        String body = "{\"id\":\"evt_1\",\"data\":{\"amount\":42}}";

        IncomingForwardAttempt row = forward(body, null);

        assertThat(new String(received.get(), StandardCharsets.UTF_8)).isEqualTo(body);
        assertThat(row.getStatus()).isEqualTo(ForwardAttemptStatus.SUCCESS);
        assertThat(row.getRequestBodySnippet()).isEqualTo(body);
    }

    @Test
    void aForwardTransformedByJsonPathRecordsTheTransformedBody() {
        IncomingForwardAttempt row = forward("{\"id\":\"evt_1\",\"data\":{\"amount\":42}}", "$.data");

        assertThat(new String(received.get(), StandardCharsets.UTF_8)).isEqualTo("{\"amount\":42}");
        assertThat(row.getStatus()).isEqualTo(ForwardAttemptStatus.SUCCESS);
        assertThat(row.getRequestBodySnippet()).isEqualTo("{\"amount\":42}");
    }

    private IncomingForwardAttempt forward(String body, String payloadTransform) {
        UUID eventId = UUID.randomUUID();
        UUID destinationId = UUID.randomUUID();
        attemptRepository.save(IncomingForwardAttempt.builder()
                .organizationId(FIXTURE_ORG)
                .incomingEventId(eventId)
                .destinationId(destinationId)
                .attemptNumber(1)
                .status(ForwardAttemptStatus.PENDING)
                .build());

        IncomingEvent event = IncomingEvent.builder()
                .id(eventId)
                .organizationId(FIXTURE_ORG)
                .incomingSourceId(UUID.randomUUID())
                .requestId(UUID.randomUUID().toString())
                .method("POST")
                .bodyRaw(body)
                .contentType("application/json")
                .receivedAt(Instant.now())
                .build();
        IncomingDestination destination = IncomingDestination.builder()
                .id(destinationId)
                .organizationId(FIXTURE_ORG)
                .incomingSourceId(event.getIncomingSourceId())
                .url(url)
                .authType(IncomingAuthType.NONE)
                .enabled(true)
                .maxAttempts(RetryLadderDefaults.INCOMING_MAX_ATTEMPTS)
                .retryDelays(RetryLadderDefaults.INCOMING_DELAYS)
                .timeoutSeconds(5)
                .payloadTransform(payloadTransform)
                .build();
        IncomingForwardMessage message = IncomingForwardMessage.builder()
                .incomingEventId(eventId).destinationId(destinationId).attemptCount(0).build();

        IncomingAttemptStore store = new IncomingAttemptStore(attemptRepository, activeProjects(),
                new TransactionTemplate(transactionManager), null, null, null, new ObjectMapper(),
                WebClient.builder().build(), null, message, event, destination);

        runner.run(store, new NoMetrics());

        return attemptRepository.findForwardAttempts(eventId, destinationId, null).get(0);
    }

    private static final class NoMetrics implements AttemptMetrics {
        @Override
        public void success(int statusCode, int durationMs) {
        }

        @Override
        public void failure(int statusCode, int durationMs) {
        }

        @Override
        public void error(int durationMs) {
        }

        @Override
        public void transformFailed() {
        }
    }

    /** Every Project active: whether a Project may still be sent for is not what this test is about. */
    private static ProjectStatusLookup activeProjects() {
        return new ProjectStatusLookup(null) {
            @Override
            public ProjectStatus forProject(UUID projectId) {
                return ProjectStatus.ACTIVE;
            }

            @Override
            public ProjectStatus forSource(UUID sourceId) {
                return ProjectStatus.ACTIVE;
            }
        };
    }
}

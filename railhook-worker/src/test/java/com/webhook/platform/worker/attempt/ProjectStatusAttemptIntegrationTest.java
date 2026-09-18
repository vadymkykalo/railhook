package com.webhook.platform.worker.attempt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.webhook.platform.common.dto.DeliveryMessage;
import com.webhook.platform.common.dto.IncomingForwardMessage;
import com.webhook.platform.common.enums.ForwardAttemptStatus;
import com.webhook.platform.common.enums.IncomingAuthType;
import com.webhook.platform.common.retry.RetryLadderDefaults;
import com.webhook.platform.common.security.EncryptionKeyRegistry;
import com.webhook.platform.worker.domain.entity.Delivery;
import com.webhook.platform.worker.domain.entity.Endpoint;
import com.webhook.platform.worker.domain.entity.Event;
import com.webhook.platform.worker.domain.entity.IncomingDestination;
import com.webhook.platform.worker.domain.entity.IncomingEvent;
import com.webhook.platform.worker.domain.entity.IncomingForwardAttempt;
import com.webhook.platform.worker.domain.repository.DeliveryAttemptRepository;
import com.webhook.platform.worker.domain.repository.DeliveryRepository;
import com.webhook.platform.worker.domain.repository.EndpointRepository;
import com.webhook.platform.worker.domain.repository.EventRepository;
import com.webhook.platform.worker.domain.repository.IncomingDestinationRepository;
import com.webhook.platform.worker.domain.repository.IncomingEventRepository;
import com.webhook.platform.worker.domain.repository.IncomingForwardAttemptRepository;
import com.webhook.platform.worker.service.CircuitBreakerService;
import com.webhook.platform.worker.service.OrderingBufferService;
import com.webhook.platform.worker.service.PayloadTransformService;
import com.webhook.platform.worker.service.ProjectRateLimiterService;
import com.webhook.platform.worker.service.RedisConcurrencyControlService;
import com.webhook.platform.worker.service.RedisRateLimiterService;
import com.webhook.platform.worker.service.TransformationCacheService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.reactive.function.client.WebClient;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * A Project the api has deleted, or an Organization an operator has suspended, sends nothing —
 * including for Deliveries and Forwards already queued or partway through their Ladder.
 *
 * <p>Against the api's real migrations rather than a schema Hibernate derives from the worker's
 * entities: the worker keeps no entity for {@code projects}, {@code organizations} or
 * {@code incoming_sources}, so a derived schema would not contain what is being read.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration,"
                + "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration,"
                + "org.springframework.boot.data.redis.autoconfigure.DataRedisRepositoriesAutoConfiguration"
})
class ProjectStatusAttemptIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("project_status")
            .withUsername("test")
            .withPassword("test");

    static {
        POSTGRES.start();
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/expected-migrations")
                .load()
                .migrate();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired private DeliveryRepository deliveryRepository;
    @Autowired private DeliveryAttemptRepository deliveryAttemptRepository;
    @Autowired private EndpointRepository endpointRepository;
    @Autowired private EventRepository eventRepository;
    @Autowired private IncomingEventRepository incomingEventRepository;
    @Autowired private IncomingDestinationRepository destinationRepository;
    @Autowired private IncomingForwardAttemptRepository forwardAttemptRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    private HttpServer server;
    private String url;
    private final AtomicInteger received = new AtomicInteger();
    private AttemptRunner runner;
    private EncryptionKeyRegistry encryptionKeyRegistry;
    private ProjectStatusLookup projectStatusLookup;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            exchange.getRequestBody().readAllBytes();
            received.incrementAndGet();
            exchange.sendResponseHeaders(200, 2);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write("ok".getBytes());
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
        runner = new AttemptRunner(tenantRateLimiter, targetRateLimiter, concurrency, circuitBreaker,
                new ObjectMapper(), true, List.of());

        // Uncached, so a status changed mid-test is what the next Attempt reads.
        projectStatusLookup = new ProjectStatusLookup(jdbc, Duration.ZERO);

        encryptionKeyRegistry = mock(EncryptionKeyRegistry.class);
        lenient().when(encryptionKeyRegistry.decryptWithFallback(anyString(), anyString(), anyInt()))
                .thenReturn("whsec_test");
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    // ── Outgoing ────────────────────────────────────────────────────────────────────────

    @Test
    void aDeliveryOfAnActiveProjectIsSent() {
        UUID org = organization();
        UUID project = project(org);

        Delivery delivery = runDelivery(pendingDelivery(org, project, endpoint(org, project)));

        assertThat(received.get()).isEqualTo(1);
        assertThat(delivery.getStatus()).isEqualTo(Delivery.DeliveryStatus.SUCCESS);
    }

    @Test
    void aDeliveryOfADeletedProjectIsNotSent_andEndsAsADeletedEndpointsDoes() {
        UUID org = organization();
        UUID deletedEndpointsProject = project(org);
        UUID deletedEndpoint = endpoint(org, deletedEndpointsProject);
        jdbc.update("UPDATE endpoints SET deleted_at = now() WHERE id = ?", deletedEndpoint);
        Delivery toDeletedEndpoint = runDelivery(pendingDelivery(org, deletedEndpointsProject, deletedEndpoint));

        UUID deletedProject = project(org);
        UUID endpoint = endpoint(org, deletedProject);
        jdbc.update("UPDATE projects SET deleted_at = now() WHERE id = ?", deletedProject);
        Delivery toDeletedProject = runDelivery(pendingDelivery(org, deletedProject, endpoint));

        assertThat(received.get()).isZero();
        assertThat(toDeletedEndpoint.getStatus()).isEqualTo(Delivery.DeliveryStatus.FAILED);
        assertThat(toDeletedProject.getStatus()).isEqualTo(Delivery.DeliveryStatus.FAILED);
        assertThat(toDeletedProject.getAttemptCount()).isZero();
    }

    @Test
    void aDeliveryOfASuspendedOrganizationIsNotSent_andGoesOutOnceTheSuspensionIsLifted() {
        UUID org = organization();
        UUID project = project(org);
        UUID delivery = pendingDelivery(org, project, endpoint(org, project));
        jdbc.update("UPDATE organizations SET suspended_at = now(), suspension_reason = 'abuse' WHERE id = ?", org);

        Delivery deferred = runDelivery(delivery);

        assertThat(received.get()).isZero();
        assertThat(deferred.getStatus()).isEqualTo(Delivery.DeliveryStatus.PENDING);
        assertThat(deferred.getAttemptCount()).isZero();
        assertThat(deferred.getClaimToken()).isNull();
        // Not re-polled in a hot loop while the suspension stands.
        assertThat(deferred.getNextRetryAt()).isAfter(Instant.now().plusSeconds(60));

        jdbc.update("UPDATE organizations SET suspended_at = NULL, suspension_reason = NULL WHERE id = ?", org);
        // The recheck comes due. Before it, a copy of the dispatch message claims nothing: the
        // Delivery is waiting on its next_retry_at, which the RetryGovernor hands out.
        assertThat(runDelivery(delivery).getAttemptCount()).isZero();
        // Through the entity, the way the application writes it: the column has no time zone,
        // so the database's own now() is not the clock the claim compares against.
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            Delivery row = deliveryRepository.findById(delivery).orElseThrow();
            row.setNextRetryAt(Instant.now().minusSeconds(1));
            deliveryRepository.save(row);
        });
        Delivery resumed = runDelivery(delivery);

        assertThat(received.get()).isEqualTo(1);
        assertThat(resumed.getStatus()).isEqualTo(Delivery.DeliveryStatus.SUCCESS);
        assertThat(resumed.getAttemptCount()).isEqualTo(1);
    }

    // ── Incoming ────────────────────────────────────────────────────────────────────────

    @Test
    void aForwardOfAnActiveProjectIsSent() {
        UUID org = organization();
        UUID source = source(org, project(org));

        IncomingForwardAttempt forward = runForward(pendingForward(org, source));

        assertThat(received.get()).isEqualTo(1);
        assertThat(forward.getStatus()).isEqualTo(ForwardAttemptStatus.SUCCESS);
    }

    @Test
    void aForwardOfADeletedProjectIsNotSent_andFails() {
        UUID org = organization();
        UUID project = project(org);
        UUID forward = pendingForward(org, source(org, project));
        jdbc.update("UPDATE projects SET deleted_at = now() WHERE id = ?", project);

        IncomingForwardAttempt row = runForward(forward);

        assertThat(received.get()).isZero();
        assertThat(row.getStatus()).isEqualTo(ForwardAttemptStatus.FAILED);
        assertThat(row.getErrorMessage()).isEqualTo("Project has been deleted");
    }

    @Test
    void aForwardOfASuspendedOrganizationIsNotSent_andGoesOutOnceTheSuspensionIsLifted() {
        UUID org = organization();
        UUID forward = pendingForward(org, source(org, project(org)));
        jdbc.update("UPDATE organizations SET suspended_at = now(), suspension_reason = 'abuse' WHERE id = ?", org);

        IncomingForwardAttempt deferred = runForward(forward);

        assertThat(received.get()).isZero();
        assertThat(deferred.getStatus()).isEqualTo(ForwardAttemptStatus.PENDING);
        assertThat(deferred.getClaimToken()).isNull();
        assertThat(deferred.getNextRetryAt()).isAfter(Instant.now().plusSeconds(60));

        jdbc.update("UPDATE organizations SET suspended_at = NULL, suspension_reason = NULL WHERE id = ?", org);
        IncomingForwardAttempt resumed = runForward(forward);

        assertThat(received.get()).isEqualTo(1);
        assertThat(resumed.getStatus()).isEqualTo(ForwardAttemptStatus.SUCCESS);
        assertThat(resumed.getAttemptNumber()).isEqualTo(1);
    }

    // ── fixtures ────────────────────────────────────────────────────────────────────────

    private UUID organization() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO organizations (id, name, plan_id) "
                + "SELECT ?, ?, id FROM plans WHERE name = 'free'", id, "Acme " + id);
        return id;
    }

    private UUID project(UUID organizationId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO projects (id, organization_id, name) VALUES (?, ?, ?)",
                id, organizationId, "Project " + id);
        return id;
    }

    private UUID endpoint(UUID organizationId, UUID projectId) {
        UUID id = UUID.randomUUID();
        endpointRepository.save(Endpoint.builder()
                .id(id)
                .organizationId(organizationId)
                .projectId(projectId)
                .url(url)
                .secretEncrypted("ciphertext")
                .secretIv("iv")
                .enabled(true)
                .verificationStatus(Endpoint.VerificationStatus.SKIPPED)
                .updatedAt(Instant.now())
                .build());
        return id;
    }

    private UUID pendingDelivery(UUID organizationId, UUID projectId, UUID endpointId) {
        Event event = eventRepository.save(Event.builder()
                .id(UUID.randomUUID())
                .organizationId(organizationId)
                .projectId(projectId)
                .eventType("order.created")
                .payload("{\"order\":1}")
                .createdAt(Instant.now())
                .build());
        UUID id = UUID.randomUUID();
        deliveryRepository.save(Delivery.builder()
                .id(id)
                .organizationId(organizationId)
                .eventId(event.getId())
                .endpointId(endpointId)
                .deliveryOrigin(Delivery.DeliveryOrigin.RULE)
                .status(Delivery.DeliveryStatus.PENDING)
                .attemptCount(0)
                .maxAttempts(3)
                .orderingEnabled(false)
                .timeoutSeconds(5)
                .retryDelays("60,300")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build());
        return id;
    }

    private UUID source(UUID organizationId, UUID projectId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO incoming_sources (id, organization_id, project_id, name, slug, ingress_path_token) "
                + "VALUES (?, ?, ?, ?, ?, ?)", id, organizationId, projectId, "Stripe", "stripe-" + id, id.toString());
        return id;
    }

    /** A Forward's first Attempt, PENDING; returns the attempt row's id. */
    private UUID pendingForward(UUID organizationId, UUID sourceId) {
        IncomingEvent event = incomingEventRepository.save(IncomingEvent.builder()
                .id(UUID.randomUUID())
                .organizationId(organizationId)
                .incomingSourceId(sourceId)
                .requestId(UUID.randomUUID().toString())
                .method("POST")
                .bodyRaw("{\"id\":\"evt_1\"}")
                .contentType("application/json")
                .receivedAt(Instant.now())
                .build());
        IncomingDestination destination = destinationRepository.save(IncomingDestination.builder()
                .id(UUID.randomUUID())
                .organizationId(organizationId)
                .incomingSourceId(sourceId)
                .url(url)
                .authType(IncomingAuthType.NONE)
                .enabled(true)
                .maxAttempts(RetryLadderDefaults.INCOMING_MAX_ATTEMPTS)
                .retryDelays(RetryLadderDefaults.INCOMING_DELAYS)
                .timeoutSeconds(5)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build());
        return forwardAttemptRepository.save(IncomingForwardAttempt.builder()
                .organizationId(organizationId)
                .incomingEventId(event.getId())
                .destinationId(destination.getId())
                .attemptNumber(1)
                .status(ForwardAttemptStatus.PENDING)
                .build()).getId();
    }

    private IncomingForwardAttempt runForward(UUID attemptId) {
        IncomingForwardAttempt row = forwardAttemptRepository.findById(attemptId).orElseThrow();
        IncomingEvent event = incomingEventRepository.findById(row.getIncomingEventId()).orElseThrow();
        IncomingDestination destination = destinationRepository.findById(row.getDestinationId()).orElseThrow();
        IncomingForwardMessage message = IncomingForwardMessage.builder()
                .incomingEventId(event.getId())
                .destinationId(destination.getId())
                .incomingSourceId(event.getIncomingSourceId())
                .attemptCount(0)
                .build();
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, IncomingForwardMessage> kafka = mock(KafkaTemplate.class);
        IncomingAttemptStore store = new IncomingAttemptStore(forwardAttemptRepository, projectStatusLookup,
                new TransactionTemplate(transactionManager), mock(TransformationCacheService.class),
                new PayloadTransformService(new ObjectMapper(), new SimpleMeterRegistry()),
                encryptionKeyRegistry, new ObjectMapper(), WebClient.builder().build(), kafka,
                message, event, destination);
        runner.run(store, new NoMetrics());
        return forwardAttemptRepository.findById(attemptId).orElseThrow();
    }

    private Delivery runDelivery(UUID deliveryId) {
        Delivery row = deliveryRepository.findById(deliveryId).orElseThrow();
        DeliveryMessage message = DeliveryMessage.builder()
                .deliveryId(deliveryId)
                .eventId(row.getEventId())
                .endpointId(row.getEndpointId())
                .status(row.getStatus().name())
                .attemptCount(0)
                .orderingEnabled(false)
                .build();
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, DeliveryMessage> kafka = mock(KafkaTemplate.class);
        OutgoingAttemptStore store = new OutgoingAttemptStore(
                deliveryRepository, deliveryAttemptRepository, endpointRepository, eventRepository,
                projectStatusLookup, new TransactionTemplate(transactionManager), mock(OrderingBufferService.class), kafka,
                encryptionKeyRegistry, null, mock(TransformationCacheService.class),
                new PayloadTransformService(new ObjectMapper(), new SimpleMeterRegistry()),
                new ObjectMapper(), WebClient.builder().build(),
                Counter.builder("test").register(new SimpleMeterRegistry()),
                Clock.systemUTC(), 5, message, false);
        runner.run(store, new NoMetrics());
        return deliveryRepository.findById(deliveryId).orElseThrow();
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
}

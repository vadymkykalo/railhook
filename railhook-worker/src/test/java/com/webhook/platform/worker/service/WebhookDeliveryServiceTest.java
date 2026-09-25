package com.webhook.platform.worker.service;

import com.webhook.platform.common.retry.RetryAfter;
import com.webhook.platform.worker.attempt.TargetFailureRecorder;
import com.webhook.platform.worker.attempt.AttemptRunner;
import java.time.Clock;
import com.webhook.platform.worker.attempt.DeliveryAttemptMetrics;
import com.webhook.platform.worker.attempt.OutgoingAttemptStoreFactory;
import com.webhook.platform.worker.attempt.ProjectStatusLookup;
import com.webhook.platform.common.retry.RetryLadderDefaults;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.webhook.platform.common.constants.KafkaTopics;
import com.webhook.platform.common.dto.DeliveryMessage;
import com.webhook.platform.common.util.PayloadCompressionUtil;
import com.webhook.platform.common.security.EncryptionKeyRegistry;
import com.webhook.platform.worker.attempt.TransformedBody;
import com.webhook.platform.worker.domain.entity.Delivery;
import com.webhook.platform.worker.domain.entity.Endpoint;
import com.webhook.platform.worker.domain.entity.Event;
import com.webhook.platform.worker.domain.entity.DeliveryAttempt;
import com.webhook.platform.worker.domain.repository.DeliveryAttemptRepository;
import com.webhook.platform.worker.domain.repository.DeliveryRepository;
import com.webhook.platform.worker.domain.repository.EndpointRepository;
import com.webhook.platform.worker.domain.repository.EventRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.redisson.api.RedissonClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;
// Adapter coverage for OutgoingAttemptStore; the attempt policy is pinned by AttemptRunnerTest.
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WebhookDeliveryServiceTest {

    /** Production default for {@code ordering.buffer-reschedule-delay-seconds}. */
    private static final int ORDERING_BUFFER_RESCHEDULE_DELAY_SECONDS = 5;

    @Mock
    private DeliveryRepository deliveryRepository;
    @Mock
    private EndpointRepository endpointRepository;
    @Mock
    private EventRepository eventRepository;
    @Mock
    private DeliveryAttemptRepository deliveryAttemptRepository;
    @Mock
    private MtlsWebClientFactory mtlsWebClientFactory;
    @Mock
    private EncryptionKeyRegistry encryptionKeyRegistry;
    @Mock
    private RedisRateLimiterService rateLimiterService;
    @Mock
    private RedisConcurrencyControlService concurrencyControlService;
    @Mock
    private ProjectRateLimiterService projectRateLimiterService;
    @Mock
    private CircuitBreakerService circuitBreakerService;
    @Mock
    private OrderingBufferService orderingBufferService;
    @Mock
    private KafkaTemplate<String, DeliveryMessage> kafkaTemplate;
    @Mock
    private PayloadTransformService payloadTransformService;
    @Mock
    private TransactionTemplate transactionTemplate;
    @Mock
    private TransformationCacheService transformationCacheService;

    private WebhookDeliveryService service;
    private MeterRegistry meterRegistry;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        when(transactionTemplate.execute(any())).thenAnswer(inv -> {
            var callback = inv.getArgument(0, TransactionCallback.class);
            return callback.doInTransaction(null);
        });
        doAnswer(inv -> {
            Consumer<Object> callback = inv.getArgument(0, Consumer.class);
            callback.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        meterRegistry = new SimpleMeterRegistry();
        service = newService(WebClient.builder().build(), meterRegistry, newAttemptRunner());
    }

    private WebhookDeliveryService newService(WebClient webClient, MeterRegistry registry, AttemptRunner runner) {
        OutgoingAttemptStoreFactory storeFactory = new OutgoingAttemptStoreFactory(
                deliveryRepository, deliveryAttemptRepository, endpointRepository, eventRepository,
                activeProjects(),
                transactionTemplate, orderingBufferService, kafkaTemplate, encryptionKeyRegistry,
                mtlsWebClientFactory, transformationCacheService, payloadTransformService,
                new ObjectMapper(), webClient, mock(TargetFailureRecorder.class), registry, Clock.systemUTC(),
                ORDERING_BUFFER_RESCHEDULE_DELAY_SECONDS);
        return new WebhookDeliveryService(runner, storeFactory, new DeliveryAttemptMetrics(registry),
                deliveryRepository, transactionTemplate);
    }

    // decryptSecret once threw outside the permit's finally, so each failure leaked a permit for good.
    @Test
    void attemptDelivery_decryptSecretThrows_releasesPermitEveryTime_soEndpointNeverBlocks() throws Exception {
        int maxConcurrent = 5;
        RedissonClient redissonClient = mock(RedissonClient.class);
        when(redissonClient.getPermitExpirableSemaphore(anyString()))
                .thenThrow(new RuntimeException("Redis unavailable in this test"));
        RedisConcurrencyControlService realConcurrencyControl = new RedisConcurrencyControlService(
                redissonClient, new SimpleMeterRegistry(), maxConcurrent, 20, 90);

        // A real concurrency control, so the permit accounting this test is about is real.
        AttemptRunner runnerWithRealPermits = new AttemptRunner(
                projectRateLimiterService, rateLimiterService, realConcurrencyControl,
                circuitBreakerService, new ObjectMapper(), true, List.of(), RetryAfter.DEFAULT_MAX_SECONDS);

        WebhookDeliveryService localService = newService(
                WebClient.builder().build(), new SimpleMeterRegistry(), runnerWithRealPermits);

        UUID endpointId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        Endpoint endpoint = Endpoint.builder()
                .id(endpointId)
                .projectId(UUID.randomUUID())
                .url("http://localhost:8080/hook")
                .secretEncrypted("cipher")
                .secretIv("iv")
                .enabled(true)
                .verificationStatus(Endpoint.VerificationStatus.VERIFIED)
                .encryptionKeyVersion(1)
                .build();
        when(endpointRepository.findById(endpointId)).thenReturn(Optional.of(endpoint));

        Event event = Event.builder()
                .id(eventId)
                .projectId(endpoint.getProjectId())
                .eventType("test.event")
                .payload("{}")
                .createdAt(Instant.now())
                .build();
        when(eventRepository.findById(eventId)).thenReturn(Optional.of(event));

        when(projectRateLimiterService.tryAcquire(endpoint.getProjectId())).thenReturn(true);
        when(circuitBreakerService.isCallPermitted(endpointId)).thenReturn(true);
        when(encryptionKeyRegistry.decryptWithFallback(anyString(), anyString(), anyInt()))
                .thenThrow(new RuntimeException("Failed to decrypt secret: key rotated away"));

        for (int i = 0; i < maxConcurrent + 1; i++) {
            UUID deliveryId = UUID.randomUUID();
            Delivery delivery = Delivery.builder()
                    .id(deliveryId)
                    .eventId(eventId)
                    .endpointId(endpointId)
                    .status(Delivery.DeliveryStatus.PROCESSING)
                    .attemptCount(0)
                    .maxAttempts(10)
                    .updatedAt(Instant.now())
                    .build();
            when(deliveryRepository.findById(deliveryId)).thenReturn(Optional.of(delivery));

            DeliveryMessage message = DeliveryMessage.builder()
                    .deliveryId(deliveryId)
                    .eventId(eventId)
                    .endpointId(endpointId)
                    .build();

            localService.processDelivery(message, true);
        }

        assertTrue(realConcurrencyControl.tryAcquireForTarget(endpointId),
                "a fixed decrypt (or any other pre-HTTP throw) must not leave the endpoint " +
                        "permanently throttled to zero — every failing attempt has to release its permit");
    }

    private Endpoint verifiedEndpoint(UUID endpointId, String url) {
        return Endpoint.builder()
                .id(endpointId)
                .projectId(UUID.randomUUID())
                .url(url)
                .secretEncrypted("cipher")
                .secretIv("iv")
                .enabled(true)
                .verificationStatus(Endpoint.VerificationStatus.VERIFIED)
                .encryptionKeyVersion(1)
                .build();
    }

    private Endpoint verifiedEndpoint(UUID endpointId, UUID projectId) {
        return Endpoint.builder()
                .id(endpointId)
                .projectId(projectId)
                .url("http://localhost:8080/hook")
                .secretEncrypted("cipher")
                .secretIv("iv")
                .enabled(true)
                .verificationStatus(Endpoint.VerificationStatus.VERIFIED)
                .encryptionKeyVersion(1)
                .build();
    }

    private Event stubEvent(UUID eventId, UUID projectId) {
        return Event.builder()
                .id(eventId)
                .projectId(projectId)
                .eventType("test.event")
                .payload("{}")
                .createdAt(Instant.now())
                .build();
    }

    private void stubHappyPathPrerequisites(Endpoint endpoint) throws Exception {
        when(projectRateLimiterService.tryAcquire(any())).thenReturn(true);
        when(circuitBreakerService.isCallPermitted(any())).thenReturn(true);
        when(concurrencyControlService.tryAcquireForTenant(any(UUID.class))).thenReturn(true);
        when(concurrencyControlService.tryAcquireForTarget(any())).thenReturn(true);
        when(encryptionKeyRegistry.decryptWithFallback(anyString(), anyString(), anyInt())).thenReturn("secret");
        when(payloadTransformService.apply(any(), anyString(), any()))
                .thenAnswer(invocation -> TransformedBody.of(invocation.getArgument(1)));
    }


    // The lifecycle lives in the Runner, so wire a real one and assert outcomes, not calls.
    private AttemptRunner newAttemptRunner() {
        return new AttemptRunner(
                projectRateLimiterService, rateLimiterService, concurrencyControlService,
                circuitBreakerService, new ObjectMapper(), true, List.of(), RetryAfter.DEFAULT_MAX_SECONDS);
    }

    private WebhookDeliveryService serviceWithMockWebClient(WebClient mockWebClient, MeterRegistry meterRegistry) {
        return newService(mockWebClient, meterRegistry, newAttemptRunner());
    }

    // Bookkeeping once ran inside the HTTP timeout, and a late timeout rewrote SUCCESS to PENDING.
    @Test
    void attemptDelivery_200ResponseFollowedBySlowSuccessBookkeeping_neverEndsPending() throws Exception {
        HttpServer httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.createContext("/hook", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().close();
        });
        httpServer.start();
        try {
            UUID endpointId = UUID.randomUUID();
            UUID eventId = UUID.randomUUID();
            UUID deliveryId = UUID.randomUUID();

            Endpoint endpoint = verifiedEndpoint(endpointId,
                    "http://127.0.0.1:" + httpServer.getAddress().getPort() + "/hook");
            when(endpointRepository.findById(endpointId)).thenReturn(Optional.of(endpoint));
            Event event = stubEvent(eventId, endpoint.getProjectId());
            when(eventRepository.findById(eventId)).thenReturn(Optional.of(event));
            stubHappyPathPrerequisites(endpoint);

            Delivery delivery = Delivery.builder()
                    .id(deliveryId).eventId(eventId).endpointId(endpointId)
                    .status(Delivery.DeliveryStatus.PROCESSING)
                    .attemptCount(0).maxAttempts(5)
                    .timeoutSeconds(1) // shorter than the slow bookkeeping below
                    .updatedAt(Instant.now())
                    .build();
            when(deliveryRepository.findById(deliveryId)).thenReturn(Optional.of(delivery));

            List<Delivery.DeliveryStatus> savedStatuses = Collections.synchronizedList(new ArrayList<>());
            AtomicReference<String> successSaveThreadName = new AtomicReference<>();
            when(deliveryRepository.save(any(Delivery.class))).thenAnswer(inv -> {
                Delivery d = inv.getArgument(0);
                if (d.getStatus() == Delivery.DeliveryStatus.SUCCESS) {
                    successSaveThreadName.set(Thread.currentThread().getName());
                    Thread.sleep(1500); // slow success bookkeeping — longer than the 1s timeout above
                }
                savedStatuses.add(d.getStatus());
                return d;
            });

            DeliveryMessage message = DeliveryMessage.builder()
                    .deliveryId(deliveryId).eventId(eventId).endpointId(endpointId).build();

            service.processDelivery(message, true);

            // Lets an unfixed build's background save land, so the test is not racy and leaks no thread.
            Thread.sleep(2000);

            assertFalse(savedStatuses.contains(Delivery.DeliveryStatus.PENDING),
                    "a delivery that already received a 2xx response must never be re-scheduled " +
                            "as a duplicate PENDING retry, even when success bookkeeping is slow " +
                            "enough to trip the HTTP timeout");
            assertTrue(savedStatuses.contains(Delivery.DeliveryStatus.SUCCESS),
                    "the 2xx response must still be recorded as SUCCESS");
            assertFalse(successSaveThreadName.get() != null
                            && successSaveThreadName.get().startsWith("reactor-http-nio"),
                    "success bookkeeping must not run on the reactor-netty event-loop thread, " +
                            "was: " + successSaveThreadName.get());
        } finally {
            httpServer.stop(0);
        }
    }

    @Test
    void scheduleRetry_rowAlreadySuccess_isNoOp() throws Exception {
        int closedPort;
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            closedPort = serverSocket.getLocalPort();
        }

        UUID endpointId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();

        Endpoint endpoint = verifiedEndpoint(endpointId, "http://127.0.0.1:" + closedPort + "/hook");
        when(endpointRepository.findById(endpointId)).thenReturn(Optional.of(endpoint));
        Event event = stubEvent(eventId, endpoint.getProjectId());
        when(eventRepository.findById(eventId)).thenReturn(Optional.of(event));
        stubHappyPathPrerequisites(endpoint);

        Delivery claimed = Delivery.builder()
                .id(deliveryId).eventId(eventId).endpointId(endpointId)
                .status(Delivery.DeliveryStatus.PROCESSING)
                .attemptCount(0).maxAttempts(5).timeoutSeconds(2)
                .updatedAt(Instant.now())
                .build();
        Delivery alreadySucceeded = Delivery.builder()
                .id(deliveryId).eventId(eventId).endpointId(endpointId)
                .status(Delivery.DeliveryStatus.SUCCESS)
                .attemptCount(1).maxAttempts(5)
                .succeededAt(Instant.now()).updatedAt(Instant.now())
                .build();
        // The second read simulates markAsSuccess winning the race.
        when(deliveryRepository.findById(deliveryId))
                .thenReturn(Optional.of(claimed), Optional.of(alreadySucceeded));

        DeliveryMessage message = DeliveryMessage.builder()
                .deliveryId(deliveryId).eventId(eventId).endpointId(endpointId).build();

        service.processDelivery(message, true);

        verify(deliveryRepository, never()).save(argThat(d -> d.getStatus() == Delivery.DeliveryStatus.PENDING));
    }

    // The park's own future next_retry_at once made the claim refuse the release message.
    @Test
    void orderingRelease_makesTheReleasedDeliveryDueBeforePublishingIt() throws Exception {
        HttpServer httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.createContext("/hook", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().close();
        });
        httpServer.start();
        try {
            UUID endpointId = UUID.randomUUID();
            UUID eventId = UUID.randomUUID();
            UUID deliveryId = UUID.randomUUID();
            UUID bufferedDeliveryId = UUID.randomUUID();

            Endpoint endpoint = verifiedEndpoint(endpointId,
                    "http://127.0.0.1:" + httpServer.getAddress().getPort() + "/hook");
            when(endpointRepository.findById(endpointId)).thenReturn(Optional.of(endpoint));
            Event event = stubEvent(eventId, endpoint.getProjectId());
            when(eventRepository.findById(eventId)).thenReturn(Optional.of(event));
            stubHappyPathPrerequisites(endpoint);

            Delivery delivery = Delivery.builder()
                    .id(deliveryId).eventId(eventId).endpointId(endpointId)
                    .status(Delivery.DeliveryStatus.PROCESSING)
                    .attemptCount(0).maxAttempts(5).timeoutSeconds(5)
                    .orderingEnabled(true).sequenceNumber(2L)
                    .updatedAt(Instant.now())
                    .build();
            when(deliveryRepository.findById(deliveryId)).thenReturn(Optional.of(delivery));

            // Parked a moment ago, so it is not due for another few seconds.
            Delivery bufferedDelivery = Delivery.builder()
                    .id(bufferedDeliveryId).eventId(eventId).endpointId(endpointId)
                    .status(Delivery.DeliveryStatus.PENDING)
                    .attemptCount(0).maxAttempts(5).sequenceNumber(3L)
                    .orderingEnabled(true)
                    .nextRetryAt(Instant.now().plusSeconds(5))
                    .updatedAt(Instant.now())
                    .build();
            when(orderingBufferService.canDeliver(endpointId, 2L)).thenReturn(true);
            when(orderingBufferService.getReadyDeliveries(endpointId)).thenReturn(List.of(bufferedDeliveryId));
            when(deliveryRepository.findAllById(List.of(bufferedDeliveryId))).thenReturn(List.of(bufferedDelivery));
            when(deliveryRepository.scheduleIfUnclaimed(eq(bufferedDeliveryId), any())).thenReturn(1);

            DeliveryMessage message = DeliveryMessage.builder()
                    .deliveryId(deliveryId).eventId(eventId).endpointId(endpointId).build();

            service.processDelivery(message, true);

            InOrder inOrder = inOrder(deliveryRepository, kafkaTemplate);
            inOrder.verify(deliveryRepository).scheduleIfUnclaimed(eq(bufferedDeliveryId),
                    argThat(dueAt -> !dueAt.isAfter(Instant.now())));
            inOrder.verify(kafkaTemplate).send(eq(KafkaTopics.DELIVERIES_DISPATCH), anyString(),
                    argThat(published -> bufferedDeliveryId.equals(published.getDeliveryId())));
        } finally {
            httpServer.stop(0);
        }
    }

    // Another attempt claimed the row, so publishing would be a duplicate nobody can act on.
    @Test
    void orderingRelease_releasedDeliveryAlreadyClaimed_publishesNothingForIt() throws Exception {
        HttpServer httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.createContext("/hook", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().close();
        });
        httpServer.start();
        try {
            UUID endpointId = UUID.randomUUID();
            UUID eventId = UUID.randomUUID();
            UUID deliveryId = UUID.randomUUID();
            UUID bufferedDeliveryId = UUID.randomUUID();

            Endpoint endpoint = verifiedEndpoint(endpointId,
                    "http://127.0.0.1:" + httpServer.getAddress().getPort() + "/hook");
            when(endpointRepository.findById(endpointId)).thenReturn(Optional.of(endpoint));
            Event event = stubEvent(eventId, endpoint.getProjectId());
            when(eventRepository.findById(eventId)).thenReturn(Optional.of(event));
            stubHappyPathPrerequisites(endpoint);

            Delivery delivery = Delivery.builder()
                    .id(deliveryId).eventId(eventId).endpointId(endpointId)
                    .status(Delivery.DeliveryStatus.PROCESSING)
                    .attemptCount(0).maxAttempts(5).timeoutSeconds(5)
                    .orderingEnabled(true).sequenceNumber(2L)
                    .updatedAt(Instant.now())
                    .build();
            when(deliveryRepository.findById(deliveryId)).thenReturn(Optional.of(delivery));

            Delivery bufferedDelivery = Delivery.builder()
                    .id(bufferedDeliveryId).eventId(eventId).endpointId(endpointId)
                    .status(Delivery.DeliveryStatus.PROCESSING)
                    .attemptCount(0).maxAttempts(5).sequenceNumber(3L)
                    .orderingEnabled(true)
                    .updatedAt(Instant.now())
                    .build();
            when(orderingBufferService.canDeliver(endpointId, 2L)).thenReturn(true);
            when(orderingBufferService.getReadyDeliveries(endpointId)).thenReturn(List.of(bufferedDeliveryId));
            when(deliveryRepository.findAllById(List.of(bufferedDeliveryId))).thenReturn(List.of(bufferedDelivery));
            when(deliveryRepository.scheduleIfUnclaimed(eq(bufferedDeliveryId), any())).thenReturn(0);

            DeliveryMessage message = DeliveryMessage.builder()
                    .deliveryId(deliveryId).eventId(eventId).endpointId(endpointId).build();

            service.processDelivery(message, true);

            verify(kafkaTemplate, never()).send(eq(KafkaTopics.DELIVERIES_DISPATCH), anyString(),
                    argThat(published -> bufferedDeliveryId.equals(published.getDeliveryId())));
        } finally {
            httpServer.stop(0);
        }
    }

    // The SUCCESS write committed in its own transaction before the Kafka call.
    @Test
    void markAsSuccess_kafkaSendFailureAfterCommit_doesNotRollBackToPending() throws Exception {
        HttpServer httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.createContext("/hook", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().close();
        });
        httpServer.start();
        try {
            UUID endpointId = UUID.randomUUID();
            UUID eventId = UUID.randomUUID();
            UUID deliveryId = UUID.randomUUID();
            UUID bufferedDeliveryId = UUID.randomUUID();

            Endpoint endpoint = verifiedEndpoint(endpointId,
                    "http://127.0.0.1:" + httpServer.getAddress().getPort() + "/hook");
            when(endpointRepository.findById(endpointId)).thenReturn(Optional.of(endpoint));
            Event event = stubEvent(eventId, endpoint.getProjectId());
            when(eventRepository.findById(eventId)).thenReturn(Optional.of(event));
            stubHappyPathPrerequisites(endpoint);

            Delivery delivery = Delivery.builder()
                    .id(deliveryId).eventId(eventId).endpointId(endpointId)
                    .status(Delivery.DeliveryStatus.PROCESSING)
                    .attemptCount(0).maxAttempts(5).timeoutSeconds(5)
                    .orderingEnabled(true).sequenceNumber(2L)
                    .updatedAt(Instant.now())
                    .build();
            when(deliveryRepository.findById(deliveryId)).thenReturn(Optional.of(delivery));

            Delivery bufferedDelivery = Delivery.builder()
                    .id(bufferedDeliveryId).eventId(eventId).endpointId(endpointId)
                    .status(Delivery.DeliveryStatus.PENDING)
                    .attemptCount(0).maxAttempts(5).sequenceNumber(3L)
                    .updatedAt(Instant.now())
                    .build();
            when(orderingBufferService.canDeliver(endpointId, 2L)).thenReturn(true);
            when(orderingBufferService.getReadyDeliveries(endpointId)).thenReturn(List.of(bufferedDeliveryId));
            when(deliveryRepository.findAllById(List.of(bufferedDeliveryId))).thenReturn(List.of(bufferedDelivery));
            when(deliveryRepository.scheduleIfUnclaimed(eq(bufferedDeliveryId), any())).thenReturn(1);
            when(kafkaTemplate.send(anyString(), anyString(), any()))
                    .thenThrow(new RuntimeException("producer buffer exhausted"));

            DeliveryMessage message = DeliveryMessage.builder()
                    .deliveryId(deliveryId).eventId(eventId).endpointId(endpointId).build();

            service.processDelivery(message, true);

            verify(deliveryRepository).save(argThat(d -> d.getStatus() == Delivery.DeliveryStatus.SUCCESS));
            verify(deliveryRepository, never()).save(argThat(d -> d.getStatus() == Delivery.DeliveryStatus.PENDING));
        } finally {
            httpServer.stop(0);
        }
    }

    // Old code fell back to the inline template, often the raw payload, instead of failing.
    @Test
    void attemptDelivery_configuredTransformationMissing_noHttpCall_failsRetryable() {
        WebClient mockWebClient = mock(WebClient.class);
        MeterRegistry meterRegistry = new SimpleMeterRegistry();
        WebhookDeliveryService localService = serviceWithMockWebClient(mockWebClient, meterRegistry);

        UUID endpointId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        Endpoint endpoint = verifiedEndpoint(endpointId, UUID.randomUUID());
        when(endpointRepository.findById(endpointId)).thenReturn(Optional.of(endpoint));

        Event event = Event.builder()
                .id(eventId)
                .projectId(endpoint.getProjectId())
                .eventType("test.event")
                .payload("{\"pii\":\"ssn-123-45-6789\"}")
                .createdAt(Instant.now())
                .build();
        when(eventRepository.findById(eventId)).thenReturn(Optional.of(event));

        when(projectRateLimiterService.tryAcquire(endpoint.getProjectId())).thenReturn(true);
        when(circuitBreakerService.isCallPermitted(endpointId)).thenReturn(true);
        when(concurrencyControlService.tryAcquireForTenant(any())).thenReturn(true);
        when(concurrencyControlService.tryAcquireForTarget(endpointId)).thenReturn(true);
        when(encryptionKeyRegistry.decryptWithFallback(anyString(), anyString(), anyInt())).thenReturn("secret");

        UUID transformationId = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();
        Delivery delivery = Delivery.builder()
                .id(deliveryId)
                .eventId(eventId)
                .endpointId(endpointId)
                .status(Delivery.DeliveryStatus.PROCESSING)
                .attemptCount(0)
                .maxAttempts(5)
                .transformationId(transformationId)
                .updatedAt(Instant.now())
                .build();
        when(deliveryRepository.findById(deliveryId)).thenReturn(Optional.of(delivery));
        // Disabled or deleted after being configured.
        when(transformationCacheService.findEnabledTemplate(transformationId)).thenReturn(null);

        DeliveryMessage message = DeliveryMessage.builder()
                .deliveryId(deliveryId).eventId(eventId).endpointId(endpointId).build();

        localService.processDelivery(message, true);

        // The raw payload must never leave the platform.
        verifyNoInteractions(mockWebClient);
        verifyNoInteractions(payloadTransformService);

        ArgumentCaptor<DeliveryAttempt> attemptCaptor = ArgumentCaptor.forClass(DeliveryAttempt.class);
        verify(deliveryAttemptRepository).save(attemptCaptor.capture());
        DeliveryAttempt savedAttempt = attemptCaptor.getValue();
        assertTrue(savedAttempt.getErrorMessage() != null && savedAttempt.getErrorMessage().contains("TRANSFORM_FAILED"),
                "the attempt must record a clear transform-failure error, not a warn-log-only fallback");
        assertEquals(null, savedAttempt.getRequestBody(), "request body must be null -- the raw payload was never built");
        assertEquals(null, savedAttempt.getHttpStatusCode(), "no HTTP response -- no call was made");

        ArgumentCaptor<Delivery> deliveryCaptor = ArgumentCaptor.forClass(Delivery.class);
        verify(deliveryRepository).save(deliveryCaptor.capture());
        // Retryable: attempt 1 of 5.
        assertEquals(Delivery.DeliveryStatus.PENDING, deliveryCaptor.getValue().getStatus());

        assertEquals(1.0, meterRegistry.get("transform_failed_total").counter().count(),
                "a configured-but-failing transform must be counted, not just warn-logged");
    }

    @Test
    void attemptDelivery_brokenPayloadTemplate_atMaxAttempts_terminatesAtDlq_noHttpCall() {
        WebClient mockWebClient = mock(WebClient.class);
        MeterRegistry meterRegistry = new SimpleMeterRegistry();
        WebhookDeliveryService localService = serviceWithMockWebClient(mockWebClient, meterRegistry);

        UUID endpointId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        Endpoint endpoint = verifiedEndpoint(endpointId, UUID.randomUUID());
        when(endpointRepository.findById(endpointId)).thenReturn(Optional.of(endpoint));

        Event event = Event.builder()
                .id(eventId)
                .projectId(endpoint.getProjectId())
                .eventType("test.event")
                .payload("{\"pii\":\"ssn-123-45-6789\"}")
                .createdAt(Instant.now())
                .build();
        when(eventRepository.findById(eventId)).thenReturn(Optional.of(event));

        when(projectRateLimiterService.tryAcquire(endpoint.getProjectId())).thenReturn(true);
        when(circuitBreakerService.isCallPermitted(endpointId)).thenReturn(true);
        when(concurrencyControlService.tryAcquireForTenant(any())).thenReturn(true);
        when(concurrencyControlService.tryAcquireForTarget(endpointId)).thenReturn(true);
        when(encryptionKeyRegistry.decryptWithFallback(anyString(), anyString(), anyInt())).thenReturn("secret");

        UUID deliveryId = UUID.randomUUID();
        Delivery delivery = Delivery.builder()
                .id(deliveryId)
                .eventId(eventId)
                .endpointId(endpointId)
                .status(Delivery.DeliveryStatus.PROCESSING)
                .attemptCount(4)
                .maxAttempts(5)
                .payloadTemplate("{ this is not valid json")
                .updatedAt(Instant.now())
                .build();
        when(deliveryRepository.findById(deliveryId)).thenReturn(Optional.of(delivery));
        when(payloadTransformService.apply(any(), anyString(), any()))
                .thenThrow(new PayloadTransformException("Payload transformation failed: broken JSON"));

        DeliveryMessage message = DeliveryMessage.builder()
                .deliveryId(deliveryId).eventId(eventId).endpointId(endpointId).build();

        localService.processDelivery(message, true);

        verifyNoInteractions(mockWebClient);

        ArgumentCaptor<Delivery> deliveryCaptor = ArgumentCaptor.forClass(Delivery.class);
        verify(deliveryRepository).save(deliveryCaptor.capture());
        assertEquals(Delivery.DeliveryStatus.DLQ, deliveryCaptor.getValue().getStatus(),
                "a permanently broken template must terminate at DLQ, not retry forever");
    }

    private Delivery baseDelivery(UUID id, UUID eventId, UUID endpointId, int attemptCount, int maxAttempts) {
        return Delivery.builder()
                .id(id).eventId(eventId).endpointId(endpointId)
                .status(Delivery.DeliveryStatus.PROCESSING)
                .attemptCount(attemptCount).maxAttempts(maxAttempts).timeoutSeconds(5)
                .updatedAt(Instant.now())
                .build();
    }

    @Test
    void attemptDelivery_2xxResponse_marksSuccess_noRetryScheduled() throws Exception {
        HttpServer httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.createContext("/hook", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().close();
        });
        httpServer.start();
        try {
            UUID endpointId = UUID.randomUUID();
            UUID eventId = UUID.randomUUID();
            UUID deliveryId = UUID.randomUUID();

            Endpoint endpoint = verifiedEndpoint(endpointId,
                    "http://127.0.0.1:" + httpServer.getAddress().getPort() + "/hook");
            when(endpointRepository.findById(endpointId)).thenReturn(Optional.of(endpoint));
            Event event = stubEvent(eventId, endpoint.getProjectId());
            when(eventRepository.findById(eventId)).thenReturn(Optional.of(event));
            stubHappyPathPrerequisites(endpoint);

            Delivery delivery = baseDelivery(deliveryId, eventId, endpointId, 0, 5);
            when(deliveryRepository.findById(deliveryId)).thenReturn(Optional.of(delivery));

            DeliveryMessage message = DeliveryMessage.builder()
                    .deliveryId(deliveryId).eventId(eventId).endpointId(endpointId).build();

            service.processDelivery(message, true);

            verify(deliveryRepository).save(argThat(d -> d.getStatus() == Delivery.DeliveryStatus.SUCCESS));
            verify(deliveryRepository, never()).save(argThat(d -> d.getStatus() == Delivery.DeliveryStatus.PENDING));
            verify(circuitBreakerService).recordSuccess(eq(endpointId), anyLong());
            verify(concurrencyControlService).releaseForTarget(endpointId);
        } finally {
            httpServer.stop(0);
        }
    }

    @Test
    void attemptDelivery_4xxNonRetryable_goesToDlq_noRetryScheduled() throws Exception {
        HttpServer httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.createContext("/hook", exchange -> {
            exchange.sendResponseHeaders(404, 0);
            exchange.getResponseBody().close();
        });
        httpServer.start();
        try {
            UUID endpointId = UUID.randomUUID();
            UUID eventId = UUID.randomUUID();
            UUID deliveryId = UUID.randomUUID();

            Endpoint endpoint = verifiedEndpoint(endpointId,
                    "http://127.0.0.1:" + httpServer.getAddress().getPort() + "/hook");
            when(endpointRepository.findById(endpointId)).thenReturn(Optional.of(endpoint));
            Event event = stubEvent(eventId, endpoint.getProjectId());
            when(eventRepository.findById(eventId)).thenReturn(Optional.of(event));
            stubHappyPathPrerequisites(endpoint);

            Delivery delivery = baseDelivery(deliveryId, eventId, endpointId, 0, 5);
            when(deliveryRepository.findById(deliveryId)).thenReturn(Optional.of(delivery));

            DeliveryMessage message = DeliveryMessage.builder()
                    .deliveryId(deliveryId).eventId(eventId).endpointId(endpointId).build();

            service.processDelivery(message, true);

            // Into Failed Messages, where a person can retry it; never FAILED, which that list omits.
            verify(deliveryRepository).save(argThat(d -> d.getStatus() == Delivery.DeliveryStatus.DLQ));
            verify(deliveryRepository, never()).save(argThat(d -> d.getStatus() == Delivery.DeliveryStatus.PENDING));
            verify(deliveryRepository, never()).save(argThat(d -> d.getStatus() == Delivery.DeliveryStatus.FAILED));
            verify(circuitBreakerService).recordFailure(eq(endpointId), any());
        } finally {
            httpServer.stop(0);
        }
    }

    @Test
    void attemptDelivery_5xxResponse_schedulesRetryAtFirstTier() throws Exception {
        HttpServer httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.createContext("/hook", exchange -> {
            exchange.sendResponseHeaders(503, 0);
            exchange.getResponseBody().close();
        });
        httpServer.start();
        try {
            UUID endpointId = UUID.randomUUID();
            UUID eventId = UUID.randomUUID();
            UUID deliveryId = UUID.randomUUID();

            Endpoint endpoint = verifiedEndpoint(endpointId,
                    "http://127.0.0.1:" + httpServer.getAddress().getPort() + "/hook");
            when(endpointRepository.findById(endpointId)).thenReturn(Optional.of(endpoint));
            Event event = stubEvent(eventId, endpoint.getProjectId());
            when(eventRepository.findById(eventId)).thenReturn(Optional.of(event));
            stubHappyPathPrerequisites(endpoint);

            // The pre-HTTP increment makes this attempt 1: the ladder's first tier (60s, jittered 30-90s).
            Delivery delivery = baseDelivery(deliveryId, eventId, endpointId, 0, 5);
            when(deliveryRepository.findById(deliveryId)).thenReturn(Optional.of(delivery));

            DeliveryMessage message = DeliveryMessage.builder()
                    .deliveryId(deliveryId).eventId(eventId).endpointId(endpointId).build();

            Instant before = Instant.now();
            service.processDelivery(message, true);

            ArgumentCaptor<Delivery> captor = ArgumentCaptor.forClass(Delivery.class);
            verify(deliveryRepository).save(captor.capture());
            Delivery saved = captor.getValue();
            assertEquals(Delivery.DeliveryStatus.PENDING, saved.getStatus());
            long secondsFromNow = saved.getNextRetryAt().getEpochSecond() - before.getEpochSecond();
            assertTrue(secondsFromNow >= 29 && secondsFromNow <= 91,
                    "expected first-tier retry (~30-90s jittered) but was " + secondsFromNow + "s");
            verify(circuitBreakerService).recordFailure(eq(endpointId), any());
        } finally {
            httpServer.stop(0);
        }
    }

    @Test
    void attemptDelivery_httpTimeout_schedulesRetry() throws Exception {
        HttpServer httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.createContext("/hook", exchange -> {
            try {
                Thread.sleep(2000); // longer than the 1s delivery timeout below
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().close();
        });
        httpServer.start();
        try {
            UUID endpointId = UUID.randomUUID();
            UUID eventId = UUID.randomUUID();
            UUID deliveryId = UUID.randomUUID();

            Endpoint endpoint = verifiedEndpoint(endpointId,
                    "http://127.0.0.1:" + httpServer.getAddress().getPort() + "/hook");
            when(endpointRepository.findById(endpointId)).thenReturn(Optional.of(endpoint));
            Event event = stubEvent(eventId, endpoint.getProjectId());
            when(eventRepository.findById(eventId)).thenReturn(Optional.of(event));
            stubHappyPathPrerequisites(endpoint);

            Delivery delivery = Delivery.builder()
                    .id(deliveryId).eventId(eventId).endpointId(endpointId)
                    .status(Delivery.DeliveryStatus.PROCESSING)
                    .attemptCount(0).maxAttempts(5).timeoutSeconds(1)
                    .updatedAt(Instant.now())
                    .build();
            when(deliveryRepository.findById(deliveryId)).thenReturn(Optional.of(delivery));

            DeliveryMessage message = DeliveryMessage.builder()
                    .deliveryId(deliveryId).eventId(eventId).endpointId(endpointId).build();

            service.processDelivery(message, true);

            verify(deliveryRepository).save(argThat(d -> d.getStatus() == Delivery.DeliveryStatus.PENDING));
            verify(deliveryRepository, never()).save(argThat(d -> d.getStatus() == Delivery.DeliveryStatus.SUCCESS));
            verify(circuitBreakerService).recordFailure(eq(endpointId), any());
            verify(concurrencyControlService).releaseForTarget(endpointId);
        } finally {
            httpServer.stop(0);
        }
    }

    @Test
    void attemptDelivery_5xxAtMaxAttempts_movesToDlq_publishesDlqEvent() throws Exception {
        HttpServer httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.createContext("/hook", exchange -> {
            exchange.sendResponseHeaders(500, 0);
            exchange.getResponseBody().close();
        });
        httpServer.start();
        try {
            UUID endpointId = UUID.randomUUID();
            UUID eventId = UUID.randomUUID();
            UUID deliveryId = UUID.randomUUID();

            Endpoint endpoint = verifiedEndpoint(endpointId,
                    "http://127.0.0.1:" + httpServer.getAddress().getPort() + "/hook");
            when(endpointRepository.findById(endpointId)).thenReturn(Optional.of(endpoint));
            Event event = stubEvent(eventId, endpoint.getProjectId());
            when(eventRepository.findById(eventId)).thenReturn(Optional.of(event));
            stubHappyPathPrerequisites(endpoint);

            // The pre-HTTP increment makes this attempt 5 of 5, so the failure terminates at DLQ.
            Delivery delivery = baseDelivery(deliveryId, eventId, endpointId, 4, 5);
            when(deliveryRepository.findById(deliveryId)).thenReturn(Optional.of(delivery));

            DeliveryMessage message = DeliveryMessage.builder()
                    .deliveryId(deliveryId).eventId(eventId).endpointId(endpointId).build();

            service.processDelivery(message, true);

            verify(deliveryRepository).save(argThat(d -> d.getStatus() == Delivery.DeliveryStatus.DLQ));
            verify(deliveryRepository, never()).save(argThat(d -> d.getStatus() == Delivery.DeliveryStatus.PENDING));
            verify(kafkaTemplate).send(eq(KafkaTopics.DELIVERIES_DLQ), eq(endpointId.toString()), any());
        } finally {
            httpServer.stop(0);
        }
    }

    @Test
    void attemptDelivery_concurrencyRejected_reschedulesWithoutHttpCall_noPermitHeldToRelease() {
        WebClient mockWebClient = mock(WebClient.class);
        MeterRegistry meterRegistry = new SimpleMeterRegistry();
        WebhookDeliveryService localService = serviceWithMockWebClient(mockWebClient, meterRegistry);

        UUID endpointId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();

        Endpoint endpoint = verifiedEndpoint(endpointId, UUID.randomUUID());
        when(endpointRepository.findById(endpointId)).thenReturn(Optional.of(endpoint));
        Event event = stubEvent(eventId, endpoint.getProjectId());
        when(eventRepository.findById(eventId)).thenReturn(Optional.of(event));

        when(projectRateLimiterService.tryAcquire(endpoint.getProjectId())).thenReturn(true);
        when(circuitBreakerService.isCallPermitted(endpointId)).thenReturn(true);
        // No endpoint rate limit, so concurrency is the blocking check.
        when(concurrencyControlService.tryAcquireForTenant(any())).thenReturn(true);
        when(concurrencyControlService.tryAcquireForTarget(endpointId)).thenReturn(false);

        Delivery delivery = baseDelivery(deliveryId, eventId, endpointId, 0, 5);
        when(deliveryRepository.findById(deliveryId)).thenReturn(Optional.of(delivery));

        DeliveryMessage message = DeliveryMessage.builder()
                .deliveryId(deliveryId).eventId(eventId).endpointId(endpointId).build();

        localService.processDelivery(message, true);

        verifyNoInteractions(mockWebClient);
        // Never acquired, so a release here would desync the permit accounting.
        verify(concurrencyControlService, never()).releaseForTarget(any());

        ArgumentCaptor<Delivery> captor = ArgumentCaptor.forClass(Delivery.class);
        verify(deliveryRepository).save(captor.capture());
        assertEquals(Delivery.DeliveryStatus.PENDING, captor.getValue().getStatus());
        assertTrue(captor.getValue().getNextRetryAt().isAfter(Instant.now()));
    }

    @Test
    void attemptDelivery_ssrfBlockedUrl_marksFailed_takesNoPermit_noHttpCall() {
        WebClient mockWebClient = mock(WebClient.class);
        MeterRegistry meterRegistry = new SimpleMeterRegistry();
        WebhookDeliveryService localService = serviceWithMockWebClient(mockWebClient, meterRegistry);

        UUID endpointId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();

        // Cloud metadata is blocked regardless of allowPrivateIps and allowedHosts.
        Endpoint endpoint = verifiedEndpoint(endpointId, "http://169.254.169.254/latest/meta-data/");
        when(endpointRepository.findById(endpointId)).thenReturn(Optional.of(endpoint));
        Event event = stubEvent(eventId, endpoint.getProjectId());
        when(eventRepository.findById(eventId)).thenReturn(Optional.of(event));

        when(projectRateLimiterService.tryAcquire(endpoint.getProjectId())).thenReturn(true);
        when(circuitBreakerService.isCallPermitted(endpointId)).thenReturn(true);
        when(concurrencyControlService.tryAcquireForTenant(any())).thenReturn(true);
        when(concurrencyControlService.tryAcquireForTarget(endpointId)).thenReturn(true);

        Delivery delivery = baseDelivery(deliveryId, eventId, endpointId, 0, 5);
        when(deliveryRepository.findById(deliveryId)).thenReturn(Optional.of(delivery));

        DeliveryMessage message = DeliveryMessage.builder()
                .deliveryId(deliveryId).eventId(eventId).endpointId(endpointId).build();

        localService.processDelivery(message, true);

        verifyNoInteractions(mockWebClient);
        // URL validation runs before admission, so a refused Delivery spends no permit or token.
        verify(concurrencyControlService, never()).tryAcquireForTarget(endpointId);
        verify(concurrencyControlService, never()).releaseForTarget(endpointId);

        ArgumentCaptor<Delivery> deliveryCaptor = ArgumentCaptor.forClass(Delivery.class);
        verify(deliveryRepository).save(deliveryCaptor.capture());
        assertEquals(Delivery.DeliveryStatus.FAILED, deliveryCaptor.getValue().getStatus());

        ArgumentCaptor<DeliveryAttempt> attemptCaptor = ArgumentCaptor.forClass(DeliveryAttempt.class);
        verify(deliveryAttemptRepository).save(attemptCaptor.capture());
        assertTrue(attemptCaptor.getValue().getErrorMessage() != null
                        && attemptCaptor.getValue().getErrorMessage().contains("SSRF_PROTECTION"),
                "attempt must record the SSRF rejection, not silently drop it");
    }

    private Delivery orderedDelivery(UUID id, UUID eventId, UUID endpointId, long sequenceNumber,
            Instant orderingFirstBufferedAt) {
        return Delivery.builder()
                .id(id).eventId(eventId).endpointId(endpointId)
                .status(Delivery.DeliveryStatus.PROCESSING)
                .attemptCount(0).maxAttempts(5).timeoutSeconds(5)
                .orderingEnabled(true).sequenceNumber(sequenceNumber)
                .orderingFirstBufferedAt(orderingFirstBufferedAt)
                .updatedAt(Instant.now())
                .build();
    }

    // Only seq-1 was once checked, so 10 went out ahead of a still-retrying 6.
    @Test
    void canDeliverWithOrdering_somethingElseInGapStillPending_buffersInsteadOfSkippingAhead() {
        MeterRegistry meterRegistry = new SimpleMeterRegistry();
        WebhookDeliveryService localService = serviceWithMockWebClient(mock(WebClient.class), meterRegistry);

        UUID endpointId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();

        Delivery delivery = orderedDelivery(deliveryId, eventId, endpointId, 10L, null);
        when(deliveryRepository.findById(deliveryId)).thenReturn(Optional.of(delivery));

        when(orderingBufferService.canDeliver(endpointId, 10L)).thenReturn(false);
        when(orderingBufferService.getLastDeliveredSequence(endpointId)).thenReturn(5L);
        // Sequence 6 (somewhere in [6, 9]) is still PENDING/PROCESSING.
        when(deliveryRepository.findOldestPendingCreatedAt(endpointId, 6L, 9L))
                .thenReturn(Instant.now().minusSeconds(5));
        when(orderingBufferService.isGapTimedOut(any())).thenReturn(false);

        DeliveryMessage message = DeliveryMessage.builder()
                .deliveryId(deliveryId).eventId(eventId).endpointId(endpointId).build();

        localService.processDelivery(message, true);

        verifyNoInteractions(endpointRepository); // never even looked up -- buffered before that
        verify(orderingBufferService).bufferDelivery(endpointId, deliveryId, 10L);

        ArgumentCaptor<Delivery> captor = ArgumentCaptor.forClass(Delivery.class);
        verify(deliveryRepository).save(captor.capture());
        assertEquals(Delivery.DeliveryStatus.PENDING, captor.getValue().getStatus());
        assertTrue(captor.getValue().getOrderingFirstBufferedAt() != null,
                "must stamp when this delivery first started waiting, for the gap timeout clock");
    }

    // A sequence burned by a rolled-back ingest will never arrive, so proceed at once.
    @Test
    void canDeliverWithOrdering_nothingOutstandingInGap_proceedsImmediately() throws Exception {
        HttpServer httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.createContext("/hook", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().close();
        });
        httpServer.start();
        try {
            UUID endpointId = UUID.randomUUID();
            UUID eventId = UUID.randomUUID();
            UUID deliveryId = UUID.randomUUID();

            Endpoint endpoint = verifiedEndpoint(endpointId,
                    "http://127.0.0.1:" + httpServer.getAddress().getPort() + "/hook");
            when(endpointRepository.findById(endpointId)).thenReturn(Optional.of(endpoint));
            Event event = stubEvent(eventId, endpoint.getProjectId());
            when(eventRepository.findById(eventId)).thenReturn(Optional.of(event));
            stubHappyPathPrerequisites(endpoint);

            Delivery delivery = orderedDelivery(deliveryId, eventId, endpointId, 10L, null);
            when(deliveryRepository.findById(deliveryId)).thenReturn(Optional.of(delivery));

            when(orderingBufferService.canDeliver(endpointId, 10L)).thenReturn(false);
            when(orderingBufferService.getLastDeliveredSequence(endpointId)).thenReturn(5L);
            when(deliveryRepository.findOldestPendingCreatedAt(endpointId, 6L, 9L)).thenReturn(null);
            when(orderingBufferService.getReadyDeliveries(endpointId)).thenReturn(List.of());

            DeliveryMessage message = DeliveryMessage.builder()
                    .deliveryId(deliveryId).eventId(eventId).endpointId(endpointId).build();

            service.processDelivery(message, true);

            verify(endpointRepository).findById(endpointId); // proceeded past the ordering check
            verify(orderingBufferService, never()).bufferDelivery(any(), any(), anyLong());
        } finally {
            httpServer.stop(0);
        }
    }

    // The first rung and the gap timeout are both a minute; the timeout once let 6 jump its retrying predecessor.
    @Test
    void canDeliverWithOrdering_gapStillClosing_staysBufferedRatherThanTimingOut() {
        UUID endpointId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();

        Instant firstBufferedAt = Instant.now().minusSeconds(90);
        Delivery delivery = orderedDelivery(deliveryId, eventId, endpointId, 6L, firstBufferedAt);
        when(deliveryRepository.findById(deliveryId)).thenReturn(Optional.of(delivery));

        when(orderingBufferService.canDeliver(endpointId, 6L)).thenReturn(false);
        when(orderingBufferService.getLastDeliveredSequence(endpointId)).thenReturn(4L);
        when(deliveryRepository.findOldestPendingCreatedAt(endpointId, 5L, 5L))
                .thenReturn(Instant.now().minusSeconds(95));
        when(orderingBufferService.isGapTimedOut(firstBufferedAt)).thenReturn(true);
        when(orderingBufferService.gapTimeout()).thenReturn(Duration.ofSeconds(60));
        // 5 is between rungs with its next Attempt due inside the window: the gap is closing.
        when(deliveryRepository.countGapClosingBefore(eq(endpointId), eq(5L), eq(5L), any(), any()))
                .thenReturn(1L);

        DeliveryMessage message = DeliveryMessage.builder()
                .deliveryId(deliveryId).eventId(eventId).endpointId(endpointId).build();

        service.processDelivery(message, true);

        verifyNoInteractions(endpointRepository); // nothing was sent
        verify(orderingBufferService).bufferDelivery(endpointId, deliveryId, 6L);
        assertEquals(0.0, meterRegistry.counter("webhook_ordering_gap_timeout_total").count(),
                "a gap that is about to close has not timed out");

        ArgumentCaptor<Delivery> captor = ArgumentCaptor.forClass(Delivery.class);
        verify(deliveryRepository).save(captor.capture());
        assertEquals(Delivery.DeliveryStatus.PENDING, captor.getValue().getStatus());
    }

    // A mocked OrderingBufferService cannot increment, so exactly 1 proves no double count.
    @Test
    void canDeliverWithOrdering_gapTimedOut_countsMetricExactlyOnce() throws Exception {
        HttpServer httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.createContext("/hook", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().close();
        });
        httpServer.start();
        try {
            UUID endpointId = UUID.randomUUID();
            UUID eventId = UUID.randomUUID();
            UUID deliveryId = UUID.randomUUID();

            Endpoint endpoint = verifiedEndpoint(endpointId,
                    "http://127.0.0.1:" + httpServer.getAddress().getPort() + "/hook");
            when(endpointRepository.findById(endpointId)).thenReturn(Optional.of(endpoint));
            Event event = stubEvent(eventId, endpoint.getProjectId());
            when(eventRepository.findById(eventId)).thenReturn(Optional.of(event));
            stubHappyPathPrerequisites(endpoint);

            // Already buffered a while ago -- long enough to have timed out.
            Instant firstBufferedAt = Instant.now().minusSeconds(120);
            Delivery delivery = orderedDelivery(deliveryId, eventId, endpointId, 10L, firstBufferedAt);
            when(deliveryRepository.findById(deliveryId)).thenReturn(Optional.of(delivery));

            when(orderingBufferService.canDeliver(endpointId, 10L)).thenReturn(false);
            when(orderingBufferService.getLastDeliveredSequence(endpointId)).thenReturn(5L);
            when(deliveryRepository.findOldestPendingCreatedAt(endpointId, 6L, 9L))
                    .thenReturn(Instant.now().minusSeconds(200)); // still "pending" in DB, but we've waited long enough
            when(orderingBufferService.isGapTimedOut(firstBufferedAt)).thenReturn(true);
            when(orderingBufferService.gapTimeout()).thenReturn(Duration.ofSeconds(60));
            // Nothing in [6, 9] is in flight or due inside the window: the gap has stopped
            // closing, which is the one case the timeout exists for.
            when(deliveryRepository.countGapClosingBefore(eq(endpointId), eq(6L), eq(9L), any(), any()))
                    .thenReturn(0L);
            when(orderingBufferService.getReadyDeliveries(endpointId)).thenReturn(List.of());

            DeliveryMessage message = DeliveryMessage.builder()
                    .deliveryId(deliveryId).eventId(eventId).endpointId(endpointId).build();

            service.processDelivery(message, true);

            verify(endpointRepository).findById(endpointId); // proceeded despite the gap
            assertEquals(1.0, meterRegistry.counter("webhook_ordering_gap_timeout_total").count());
        } finally {
            httpServer.stop(0);
        }
    }

    // The worker once left payload_compressed unmapped and sent the gzip+Base64 blob as the body.
    @Test
    void processDelivery_compressedEventPayload_isDecompressedBeforeTransformAndSend() throws Exception {
        UUID deliveryId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID endpointId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();

        String realJson = "{\"order\":\"" + "x".repeat(2000) + "\"}";
        PayloadCompressionUtil.CompressionResult compressed = PayloadCompressionUtil.compress(realJson, 1024);
        assertTrue(compressed.compressed(),
                "fixture must actually be compressed, otherwise the test proves nothing");

        Event event = Event.builder()
                .id(eventId).projectId(projectId).eventType("order.created")
                .payload(compressed.payload())
                .payloadCompressed(true)
                .createdAt(Instant.now())
                .build();

        Endpoint endpoint = verifiedEndpoint(endpointId, projectId);
        Delivery delivery = Delivery.builder()
                .id(deliveryId).eventId(eventId).endpointId(endpointId)
                .status(Delivery.DeliveryStatus.PROCESSING)
                .attemptCount(0).maxAttempts(5).timeoutSeconds(1)
                .updatedAt(Instant.now())
                .build();

        when(deliveryRepository.findById(deliveryId)).thenReturn(Optional.of(delivery));
        when(endpointRepository.findById(endpointId)).thenReturn(Optional.of(endpoint));
        when(eventRepository.findById(eventId)).thenReturn(Optional.of(event));
        stubHappyPathPrerequisites(endpoint);

        service.processDelivery(DeliveryMessage.builder()
                .deliveryId(deliveryId).eventId(eventId).endpointId(endpointId).build(), true);

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(payloadTransformService).apply(any(), payloadCaptor.capture(), any());
        assertEquals(realJson, payloadCaptor.getValue(),
                "the transform (and therefore the body and the signature) must see real JSON");
    }

    // The worker once left deleted_at unmapped and kept delivering to deleted endpoints.
    @Test
    void processDelivery_softDeletedEndpoint_failsWithoutSending() throws Exception {
        UUID deliveryId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID endpointId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();

        Endpoint deleted = verifiedEndpoint(endpointId, projectId);
        deleted.setDeletedAt(Instant.now());

        Delivery delivery = Delivery.builder()
                .id(deliveryId).eventId(eventId).endpointId(endpointId)
                .status(Delivery.DeliveryStatus.PROCESSING)
                .attemptCount(0).maxAttempts(5).timeoutSeconds(1)
                .updatedAt(Instant.now())
                .build();

        when(deliveryRepository.findById(deliveryId)).thenReturn(Optional.of(delivery));
        when(endpointRepository.findById(endpointId)).thenReturn(Optional.of(deleted));
        when(deliveryRepository.save(any(Delivery.class))).thenAnswer(inv -> inv.getArgument(0));

        service.processDelivery(DeliveryMessage.builder()
                .deliveryId(deliveryId).eventId(eventId).endpointId(endpointId).build(), true);

        verify(eventRepository, never()).findById(any());
        verifyNoInteractions(payloadTransformService);
        ArgumentCaptor<Delivery> captor = ArgumentCaptor.forClass(Delivery.class);
        verify(deliveryRepository, atLeastOnce()).save(captor.capture());
        assertTrue(captor.getAllValues().stream()
                        .anyMatch(d -> d.getStatus() == Delivery.DeliveryStatus.FAILED),
                "a soft-deleted endpoint must terminally fail the delivery");
    }

    @Test
    void processDelivery_malformedRetryLadder_failsTerminallyWithoutSending() {
        // An unparseable ladder must terminate on the first pass, not cycle through the stuck sweep.
        UUID endpointId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();

        Endpoint endpoint = verifiedEndpoint(endpointId, UUID.randomUUID());
        when(endpointRepository.findById(endpointId)).thenReturn(Optional.of(endpoint));
        when(eventRepository.findById(eventId)).thenReturn(Optional.of(stubEvent(eventId, endpoint.getProjectId())));

        Delivery delivery = baseDelivery(deliveryId, eventId, endpointId, 0, 5);
        delivery.setRetryDelays("60,oops,900");
        when(deliveryRepository.findById(deliveryId)).thenReturn(Optional.of(delivery));

        DeliveryMessage message = DeliveryMessage.builder()
                .deliveryId(deliveryId).eventId(eventId).endpointId(endpointId).build();

        service.processDelivery(message, true);

        ArgumentCaptor<Delivery> saved = ArgumentCaptor.forClass(Delivery.class);
        verify(deliveryRepository).save(saved.capture());
        assertEquals(Delivery.DeliveryStatus.FAILED, saved.getValue().getStatus(),
                "an unusable ladder must terminate the delivery, not schedule another attempt");

        // Nothing was sent, and no permit was ever taken: the check runs before admission.
        verify(concurrencyControlService, never()).tryAcquireForTarget(endpointId);
        verify(deliveryRepository, never()).incrementAttemptCount(any(), any());
    }

    @Test
    void processDelivery_validRetryLadder_proceedsPastTheLadderCheck() {
        // Companion to the above: the guard must not reject the ladders the platform ships.
        UUID endpointId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();

        Endpoint endpoint = verifiedEndpoint(endpointId, UUID.randomUUID());
        when(endpointRepository.findById(endpointId)).thenReturn(Optional.of(endpoint));
        when(eventRepository.findById(eventId)).thenReturn(Optional.of(stubEvent(eventId, endpoint.getProjectId())));
        // Admission runs breaker, then permits, then rate limiters: cheapest to undo first.
        when(circuitBreakerService.isCallPermitted(any())).thenReturn(true);
        when(concurrencyControlService.tryAcquireForTenant(any())).thenReturn(true);
        when(concurrencyControlService.tryAcquireForTarget(any())).thenReturn(true);
        when(projectRateLimiterService.tryAcquire(any())).thenReturn(false); // stop right after the check

        Delivery delivery = baseDelivery(deliveryId, eventId, endpointId, 0, 5);
        delivery.setRetryDelays(RetryLadderDefaults.OUTGOING_DELAYS);
        when(deliveryRepository.findById(deliveryId)).thenReturn(Optional.of(delivery));

        DeliveryMessage message = DeliveryMessage.builder()
                .deliveryId(deliveryId).eventId(eventId).endpointId(endpointId).build();

        service.processDelivery(message, true);

        verify(projectRateLimiterService).tryAcquire(endpoint.getProjectId());
    }

    // Every Project active: project status is not what this test is about.
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

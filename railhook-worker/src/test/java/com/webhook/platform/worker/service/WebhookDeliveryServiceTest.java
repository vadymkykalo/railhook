package com.webhook.platform.worker.service;

import com.webhook.platform.worker.attempt.AttemptRunner;
import java.time.Clock;
import com.webhook.platform.worker.attempt.DeliveryAttemptMetrics;
import com.webhook.platform.worker.attempt.OutgoingAttemptStoreFactory;
import com.webhook.platform.common.retry.RetryLadderDefaults;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.webhook.platform.common.constants.KafkaTopics;
import com.webhook.platform.common.dto.DeliveryMessage;
import com.webhook.platform.common.util.PayloadCompressionUtil;
import com.webhook.platform.common.security.EncryptionKeyRegistry;
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
/**
 * Covers the Outgoing {@link com.webhook.platform.worker.attempt.OutgoingAttemptStore} through
 * the service that drives it: the {@code claim_token} fence, the FIFO ordering gate, the row
 * transitions, the {@code delivery_attempts} log and the DLQ notification.
 *
 * <p>The attempt <em>policy</em> — what order things happen in, when a successor is queued,
 * what a deferral means — moved to {@link com.webhook.platform.worker.attempt.AttemptRunner}
 * and is pinned by {@code AttemptRunnerTest} against a fake store, with no infrastructure. The
 * two suites therefore overlap in what they assert and not in what they cover: delete a case
 * here and the store loses its only test.
 */

/**
 * Covers WebhookDeliveryService.rescheduleForBackpressure — the fix for
 * DeliveryConsumer's executor-full path, which used to leave the Kafka record unacked
 * and rely on redelivery that MANUAL acks don't actually provide. See DeliveryConsumerTest
 * for the consumer-side wiring (reschedule-then-ack) and KafkaAckOrderingIntegrationTest
 * for the underlying offset-ordering fix.
 */
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
                transactionTemplate, orderingBufferService, kafkaTemplate, encryptionKeyRegistry,
                mtlsWebClientFactory, transformationCacheService, payloadTransformService,
                new ObjectMapper(), webClient, registry, Clock.systemUTC(),
                ORDERING_BUFFER_RESCHEDULE_DELAY_SECONDS);
        return new WebhookDeliveryService(runner, storeFactory, new DeliveryAttemptMetrics(registry),
                deliveryRepository, transactionTemplate);
    }

    private Delivery pendingDelivery(UUID id) {
        return Delivery.builder()
                .id(id)
                .status(Delivery.DeliveryStatus.PENDING)
                .attemptCount(0)
                .maxAttempts(5)
                .updatedAt(Instant.now().minusSeconds(60))
                .build();
    }

    private Delivery processingDelivery(UUID id) {
        return Delivery.builder()
                .id(id)
                .status(Delivery.DeliveryStatus.PROCESSING)
                .attemptCount(1)
                .maxAttempts(5)
                .updatedAt(Instant.now().minusSeconds(60))
                .build();
    }

    @Test
    void rescheduleForBackpressure_dispatchPath_reschedulesPendingDelivery() {
        UUID id = UUID.randomUUID();
        when(deliveryRepository.findById(id)).thenReturn(Optional.of(pendingDelivery(id)));

        service.rescheduleForBackpressure(id, false);

        ArgumentCaptor<Delivery> captor = ArgumentCaptor.forClass(Delivery.class);
        verify(deliveryRepository).save(captor.capture());
        Delivery saved = captor.getValue();
        assertEquals(Delivery.DeliveryStatus.PENDING, saved.getStatus());
        assertTrue(saved.getNextRetryAt() != null && saved.getNextRetryAt().isAfter(Instant.now()),
                "next_retry_at must be set to a near-future instant so RetrySchedulerService picks it up");
    }

    @Test
    void rescheduleForBackpressure_retryPath_revertsProcessingClaimToPending() {
        UUID id = UUID.randomUUID();
        when(deliveryRepository.findById(id)).thenReturn(Optional.of(processingDelivery(id)));

        service.rescheduleForBackpressure(id, true);

        ArgumentCaptor<Delivery> captor = ArgumentCaptor.forClass(Delivery.class);
        verify(deliveryRepository).save(captor.capture());
        Delivery saved = captor.getValue();
        assertEquals(Delivery.DeliveryStatus.PENDING, saved.getStatus());
        assertTrue(saved.getNextRetryAt() != null && saved.getNextRetryAt().isAfter(Instant.now()));
    }

    @Test
    void rescheduleForBackpressure_dispatchPath_noOp_whenAlreadyClaimed() {
        // A different pool thread already claimed it (PENDING -> PROCESSING) between the
        // executor-full rejection and this call — nothing to reschedule.
        UUID id = UUID.randomUUID();
        when(deliveryRepository.findById(id)).thenReturn(Optional.of(processingDelivery(id)));

        service.rescheduleForBackpressure(id, false);

        verify(deliveryRepository, never()).save(any());
    }

    @Test
    void rescheduleForBackpressure_retryPath_noOp_whenNoLongerProcessing() {
        UUID id = UUID.randomUUID();
        when(deliveryRepository.findById(id)).thenReturn(Optional.of(pendingDelivery(id)));

        service.rescheduleForBackpressure(id, true);

        verify(deliveryRepository, never()).save(any());
    }

    @Test
    void rescheduleForBackpressure_noOp_whenDeliveryMissing() {
        UUID id = UUID.randomUUID();
        when(deliveryRepository.findById(id)).thenReturn(Optional.empty());

        service.rescheduleForBackpressure(id, false);

        verify(deliveryRepository, never()).save(any());
    }

    /**
     * decryptSecret (bad key version / rotated-away key) used to throw outside the
     * try/finally that releases the concurrency permit, so every failing attempt burned a
     * permit that never came back. maxConcurrentPerEndpoint + 1 failing attempts against the
     * REAL RedisConcurrencyControlService (a mocked RedissonClient forces its local-fallback
     * path, so no Docker/Redis is needed) reproduces the leak on unfixed code: the semaphore
     * exhausts and a subsequent acquire is rejected, i.e. the endpoint is permanently blocked.
     * On fixed code every attempt releases its permit in the finally, so the endpoint never
     * blocks and a later attempt (the "operator fixed the cert" case) can still acquire.
     */
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
                circuitBreakerService, new ObjectMapper(), true, List.of());

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

    // --- a successful 2xx delivery must never be re-sent as a duplicate ------------

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

    // --- a configured transformation that fails to apply must never result in the ---
    // --- raw payload being sent, and must fail the attempt as retryable / eventually DLQ.  ---

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
        when(payloadTransformService.transform(anyString(), any())).thenReturn("{}");
    }


    /**
     * A real AttemptRunner over the same mocks the service used to hold directly. The lifecycle
     * these tests describe now lives in the Runner, so exercising it through the service means
     * wiring a real one rather than a mock — which is also what keeps these tests honest about
     * the seam: they assert observable outcomes, not who called whom.
     */
    private AttemptRunner newAttemptRunner() {
        return new AttemptRunner(
                projectRateLimiterService, rateLimiterService, concurrencyControlService,
                circuitBreakerService, new ObjectMapper(), true, List.of());
    }

    private WebhookDeliveryService serviceWithMockWebClient(WebClient mockWebClient, MeterRegistry meterRegistry) {
        return newService(mockWebClient, meterRegistry, newAttemptRunner());
    }

    /**
     * Reproduces the defect: handleResponse (markAsSuccess et al.) used to run inside
     * the reactive .map, i.e. inside the .timeout guarding the HTTP call itself. A 200 response
     * followed by slow success bookkeeping tripped the timeout AFTER the row was already
     * written SUCCESS, and the resulting TimeoutException drove scheduleRetry to blindly
     * overwrite it back to PENDING — a duplicate send of an already-successful webhook.
     * <p>
     * On unfixed code this test fails: a PENDING save is observed. On fixed code, bookkeeping
     * runs after block() returns (off the netty event-loop thread) and is no longer subject to
     * the HTTP timeout, so the delivery only ever ends up SUCCESS.
     */
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

            // Give a still-in-flight background thread (unfixed code: the netty event-loop
            // thread finishing its slow save inside .map) time to land before asserting, so the
            // test isn't racy and doesn't leak a running thread into the next test.
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

    /**
     * Direct coverage of scheduleRetry's own guard: if the row already reached SUCCESS via
     * another path by the time scheduleRetry re-reads it, scheduling a retry must be a no-op
     * rather than blindly overwriting the terminal state back to PENDING.
     */
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
        // First read is processDelivery's own claim check (PROCESSING); the second is
        // scheduleRetry's fresh re-read, simulating that markAsSuccess won the race and
        // already committed SUCCESS in between.
        when(deliveryRepository.findById(deliveryId))
                .thenReturn(Optional.of(claimed), Optional.of(alreadySucceeded));

        DeliveryMessage message = DeliveryMessage.builder()
                .deliveryId(deliveryId).eventId(eventId).endpointId(endpointId).build();

        service.processDelivery(message, true);

        verify(deliveryRepository, never()).save(argThat(d -> d.getStatus() == Delivery.DeliveryStatus.PENDING));
    }

    /**
     * A Kafka send failure while releasing the ordering buffer after a successful delivery
     * must not roll back the SUCCESS write — the DB commit already happened in its own
     * transaction before the Kafka call runs.
     */
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

    /**
     * Reproduces the original bug for the "transformationId not found/disabled" site:
     * the delivery had an explicit transformationId configured (e.g. the transformation was
     * later disabled or deleted), and old code silently fell back to the inline
     * payloadTemplate (often null -> the raw payload) instead of failing the attempt.
     */
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
        // Simulates the transformation being disabled/deleted after being configured.
        when(transformationCacheService.findEnabledTemplate(transformationId)).thenReturn(null);

        DeliveryMessage message = DeliveryMessage.builder()
                .deliveryId(deliveryId).eventId(eventId).endpointId(endpointId).build();

        localService.processDelivery(message, true);

        // No HTTP call must have been attempted -- the raw payload must never leave the platform.
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
        // Retryable: attemptCount(1) < maxAttempts(5) -- scheduled for retry, not terminal.
        assertEquals(Delivery.DeliveryStatus.PENDING, deliveryCaptor.getValue().getStatus());

        assertEquals(1.0, meterRegistry.get("transform_failed_total").counter().count(),
                "a configured-but-failing transform must be counted, not just warn-logged");
    }

    /**
     * Check the DLQ path: a permanently broken template must eventually terminate at DLQ
     * rather than retrying forever, exactly like an HTTP-level failure would. This exercises
     * the other bug site -- PayloadTransformService.transform() itself throwing for a broken
     * inline payloadTemplate -- with the delivery already on its last attempt.
     */
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
        when(payloadTransformService.transform(anyString(), anyString()))
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

    // --- basic stateful-path coverage (2xx/4xx/5xx/timeout/DLQ/concurrency/SSRF) ---

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
    void attemptDelivery_4xxNonRetryable_marksFailed_noRetryScheduled() throws Exception {
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

            verify(deliveryRepository).save(argThat(d -> d.getStatus() == Delivery.DeliveryStatus.FAILED));
            verify(deliveryRepository, never()).save(argThat(d -> d.getStatus() == Delivery.DeliveryStatus.PENDING));
            verify(deliveryRepository, never()).save(argThat(d -> d.getStatus() == Delivery.DeliveryStatus.DLQ));
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

            // attemptCount=0 -> after the pre-HTTP increment it becomes 1, i.e. the first
            // tier of the default retry ladder (60s, jittered 30s-90s).
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

            // attemptCount=4, maxAttempts=5 -> after the pre-HTTP increment attemptCount
            // becomes 5, i.e. >= maxAttempts, so this failure must terminate at DLQ.
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
        // No rate limit configured on this endpoint (rateLimitPerSecond is null), so the
        // rate limiter branch is skipped entirely -- concurrency is the blocking check.
        when(concurrencyControlService.tryAcquireForTenant(any())).thenReturn(true);
        when(concurrencyControlService.tryAcquireForTarget(endpointId)).thenReturn(false);

        Delivery delivery = baseDelivery(deliveryId, eventId, endpointId, 0, 5);
        when(deliveryRepository.findById(deliveryId)).thenReturn(Optional.of(delivery));

        DeliveryMessage message = DeliveryMessage.builder()
                .deliveryId(deliveryId).eventId(eventId).endpointId(endpointId).build();

        localService.processDelivery(message, true);

        verifyNoInteractions(mockWebClient);
        // Concurrency was never actually acquired, so there must be nothing to release --
        // a spurious release() here would desync the permit accounting.
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

        // Cloud-metadata endpoint: unconditionally blocked by UrlValidator regardless of
        // allowPrivateIps/allowedHosts.
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
        // URL validation now runs BEFORE admission, so a Delivery the platform is not allowed
        // to send never spends a concurrency permit or a rate-limit token on being rejected.
        // Previously the permit was taken first and released in the finally; the permit
        // accounting for the paths that DO take one — a decryption failure on a rotated key, a
        // bad client certificate — is covered by
        // attemptDelivery_decryptSecretThrows_releasesPermitEveryTime_soEndpointNeverBlocks.
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

    // ── gap check spans the full missing range, not just seq-1 ────

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

    /**
     * Reproduces Scenario A: cursor is at 5, sequence 6 is genuinely still
     * outstanding (retrying), and sequence 10 arrives. The old code only checked sequence 9
     * (already SUCCESS, so absent from PENDING/PROCESSING) via findOldestPendingCreatedAt(...,
     * 9), got null back, and isGapTimedOut(null) used to mean "proceed" -- so 10 was delivered
     * ahead of 6, breaking FIFO without ever waiting out the gap timeout. The fixed range query
     * covers the whole gap [6, 9] and finds 6 still outstanding, so this must buffer instead.
     */
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

    /**
     * Mirror of the case above but with nothing left outstanding anywhere in the gap (e.g. the
     * missing sequence was burned by a rolled-back ingest and will never arrive) -- must
     * proceed immediately rather than waiting out a timeout for something that isn't there.
     */
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

    /**
     * webhook_ordering_gap_timeout_total used to be incremented in both
     * OrderingBufferService.isGapTimedOut and WebhookDeliveryService.canDeliverWithOrdering.
     * With OrderingBufferService fully mocked here (its own increment can't fire), a count of
     * exactly 1 confirms WebhookDeliveryService's own increment is the only one left.
     */
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

    /**
     * The api compresses payloads above WEBHOOK_PAYLOAD_COMPRESSION_THRESHOLD_BYTES (1 KB by
     * default) and reads them back through getDecompressedPayload(). The worker Event entity
     * did not map payload_compressed at all, so it read the stored column directly and sent --
     * and signed -- the gzip+Base64 blob as the webhook body for every event at or above the
     * threshold.
     */
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
        verify(payloadTransformService).transform(payloadCaptor.capture(), any());
        assertEquals(realJson, payloadCaptor.getValue(),
                "the transform (and therefore the body and the signature) must see real JSON");
    }

    /**
     * deleteEndpoint is a soft delete: it stamps deleted_at and leaves `enabled` alone, and
     * every api-side query filters on deleted_at IS NULL. The worker entity did not map the
     * column, so already-queued deliveries kept being sent to a deleted endpoint for as long
     * as the retry ladder ran -- up to the 24h rung.
     */
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

    // --- an unusable retry ladder is a terminal configuration failure, not a retry ---

    @Test
    void processDelivery_malformedRetryLadder_failsTerminallyWithoutSending() {
        // Retrying cannot fix a ladder that does not parse, and letting RetryLadder throw
        // from inside scheduleRetry would leave the row PROCESSING for StuckDeliveryRecovery
        // to hand back, failing the same way forever. It must terminate on the first pass.
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
        verify(deliveryRepository, never()).incrementAttemptCount(any());
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
        // Admission is now ordered by what a refusal costs to undo: the breaker first (it
        // consumes nothing), then the two concurrency permits (releasable), then the rate
        // limiters (a token cannot be un-consumed). So this test has to get past three checks
        // to still stop where it means to.
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
}

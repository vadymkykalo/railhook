package com.webhook.platform.worker.service;

import com.webhook.platform.common.constants.KafkaTopics;
import org.springframework.kafka.core.KafkaTemplate;
import com.webhook.platform.worker.attempt.AttemptRunner;
import com.webhook.platform.worker.attempt.ForwardAttemptMetrics;
import com.webhook.platform.worker.attempt.IncomingAttemptStoreFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.common.dto.IncomingForwardMessage;
import com.webhook.platform.common.enums.ForwardAttemptStatus;
import com.webhook.platform.common.enums.IncomingAuthType;
import com.webhook.platform.worker.domain.entity.IncomingDestination;
import com.webhook.platform.worker.domain.entity.IncomingEvent;
import com.webhook.platform.worker.domain.entity.IncomingForwardAttempt;
import com.webhook.platform.common.security.EncryptionKeyRegistry;
import com.webhook.platform.worker.domain.repository.IncomingDestinationRepository;
import com.webhook.platform.worker.domain.repository.IncomingEventRepository;
import com.webhook.platform.worker.domain.repository.IncomingForwardAttemptRepository;
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
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
/**
 * Covers the Incoming {@link com.webhook.platform.worker.attempt.IncomingAttemptStore} through
 * the service that drives it: the three claim paths and their {@code started_at} fence, the
 * per-attempt row model, successor insertion, and the transformation resolution.
 *
 * <p>As with the Outgoing suite, the attempt policy itself lives in
 * {@link com.webhook.platform.worker.attempt.AttemptRunner} and is pinned by
 * {@code AttemptRunnerTest}. What is here is adapter coverage and has nowhere else to live.
 */

/**
 * Tests for the claim-based idempotency logic in IncomingForwardService.
 *
 * Verifies that:
 *   - First dispatch claims the existing PENDING row (created by IngressService)
 *     via atomic UPDATE, instead of INSERT-ing a duplicate.
 *   - Retry dispatch uses the attemptNumber from the scheduler message directly,
 *     without re-claiming (scheduler already set PROCESSING).
 *   - SSRF failures claim-then-update the existing row instead of INSERT-ing.
 *   - Duplicate Kafka deliveries are safely idempotent (claim returns 0 -> skip).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IncomingForwardServiceTest {

    /**
     * The claim_token the store generated for the claim under test (V060).
     *
     * <p>Against a real database the claiming UPDATE writes this onto the row, so the read
     * that {@code finalise} performs finds it there and the fence matches. With a mocked
     * repository nothing carries it across, so {@link #stampClaimToken} does what the UPDATE
     * would have done — otherwise every fixture row looks like one that was reclaimed by a
     * stuck sweep, and finalise correctly refuses to write it.</p>
     */
    private final java.util.concurrent.atomic.AtomicReference<UUID> claimedToken =
            new java.util.concurrent.atomic.AtomicReference<>();

    /** Returns the row as the claiming UPDATE would have left it. */
    private List<IncomingForwardAttempt> asClaimed(IncomingForwardAttempt attempt) {
        if (attempt.getStatus() == ForwardAttemptStatus.PROCESSING && attempt.getClaimToken() == null) {
            attempt.setClaimToken(claimedToken.get());
        }
        return List.of(attempt);
    }

    @Mock
    private IncomingEventRepository eventRepository;
    @Mock
    private IncomingDestinationRepository destinationRepository;
    @Mock
    private IncomingForwardAttemptRepository attemptRepository;
    @Mock
    private TransformationCacheService transformationCacheService;
    @Mock
    private PayloadTransformService payloadTransformService;
    @Mock
    private EncryptionKeyRegistry encryptionKeyRegistry;
    @Mock
    private TransactionTemplate transactionTemplate;
    @Mock
    private RedisConcurrencyControlService concurrencyControlService;
    @Mock
    private CircuitBreakerService circuitBreakerService;
    @Mock
    private ProjectRateLimiterService projectRateLimiterService;

    @Mock
    private KafkaTemplate<String, IncomingForwardMessage> kafkaTemplate;

    // Incoming Destinations carry no per-target rate limit, so the Runner never consults
    // this one for this direction. Present only to satisfy its constructor.
    @Mock
    private RedisRateLimiterService redisRateLimiterService;

    private IncomingForwardService service;

    private final UUID eventId = UUID.randomUUID();
    private final UUID destinationId = UUID.randomUUID();
    private final UUID sourceId = UUID.randomUUID();

    @SuppressWarnings("unchecked")
    private void stubTransactionTemplate() {
        when(transactionTemplate.execute(any())).thenAnswer(inv -> {
            var callback = inv.getArgument(0, TransactionCallback.class);
            return callback.doInTransaction(null);
        });
        lenient().doAnswer(inv -> {
            Consumer<Object> callback = inv.getArgument(0, Consumer.class);
            callback.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    @BeforeEach
    void setUp() {
        stubTransactionTemplate();

        // Tenant isolation guards — permissive by default
        when(projectRateLimiterService.tryAcquire(any(UUID.class))).thenReturn(true);
        when(circuitBreakerService.isCallPermitted(any(UUID.class))).thenReturn(true);
        when(concurrencyControlService.tryAcquireForTenant(any(UUID.class))).thenReturn(true);
        when(concurrencyControlService.tryAcquireForTarget(any(UUID.class))).thenReturn(true);

        service = newService(WebClient.builder().build(), new SimpleMeterRegistry(), newAttemptRunner(true));
    }

    private IncomingForwardService newService(WebClient webClient, MeterRegistry registry, AttemptRunner runner) {
        IncomingAttemptStoreFactory storeFactory = new IncomingAttemptStoreFactory(
                attemptRepository, transactionTemplate, transformationCacheService,
                payloadTransformService, encryptionKeyRegistry, new ObjectMapper(),
                webClient, kafkaTemplate);
        return new IncomingForwardService(eventRepository, destinationRepository, attemptRepository,
                transactionTemplate, runner, storeFactory, new ForwardAttemptMetrics(registry));
    }


    /**
     * A real AttemptRunner over the same mocks the service used to hold directly. The attempt
     * lifecycle these tests describe now lives in the Runner, so exercising it through the
     * service means wiring a real one rather than a mock.
     */
    private AttemptRunner newAttemptRunner(boolean allowPrivateIps) {
        return new AttemptRunner(
                projectRateLimiterService, redisRateLimiterService, concurrencyControlService,
                circuitBreakerService, new ObjectMapper(), allowPrivateIps, List.of());
    }

    private IncomingEvent buildEvent() {
        return IncomingEvent.builder()
                .id(eventId).incomingSourceId(sourceId)
                .requestId("req-1").method("POST")
                .bodyRaw("{\"data\":1}").contentType("application/json")
                .receivedAt(Instant.now())
                .build();
    }

    private IncomingDestination buildDestination() {
        return IncomingDestination.builder()
                .id(destinationId).incomingSourceId(sourceId)
                .url("https://example.com/hook")
                .authType(IncomingAuthType.NONE)
                .enabled(true).maxAttempts(5).timeoutSeconds(30)
                .retryDelays("60,300")
                .build();
    }

    // -- First dispatch: claims existing PENDING row --

    @Test
    void firstDispatch_claimsExistingPendingRow_notInsert() {
        when(eventRepository.findById(eventId)).thenReturn(Optional.of(buildEvent()));
        when(destinationRepository.findById(destinationId)).thenReturn(Optional.of(buildDestination()));
        when(attemptRepository.claimForProcessing(eq(eventId), eq(destinationId), eq(1), isNull(), any(UUID.class)))
                .thenAnswer(inv -> { claimedToken.set(inv.getArgument(4)); return 1; });

        IncomingForwardMessage message = IncomingForwardMessage.builder()
                .incomingEventId(eventId).destinationId(destinationId)
                .incomingSourceId(sourceId).attemptCount(0).replay(false)
                .build();

        service.processForward(message);

        // Must claim via UPDATE, never INSERT
        verify(attemptRepository).claimForProcessing(eq(eventId), eq(destinationId), eq(1), isNull(), any(UUID.class));
        verify(attemptRepository, never()).saveAndFlush(any(IncomingForwardAttempt.class));
    }

    @Test
    void firstDispatch_alreadyClaimed_skipsIdempotently() {
        when(eventRepository.findById(eventId)).thenReturn(Optional.of(buildEvent()));
        when(destinationRepository.findById(destinationId)).thenReturn(Optional.of(buildDestination()));
        when(attemptRepository.claimForProcessing(eq(eventId), eq(destinationId), eq(1), isNull(), any(UUID.class))).thenReturn(0);

        IncomingForwardMessage message = IncomingForwardMessage.builder()
                .incomingEventId(eventId).destinationId(destinationId)
                .incomingSourceId(sourceId).attemptCount(0).replay(false)
                .build();

        service.processForward(message);

        verify(attemptRepository).claimForProcessing(eq(eventId), eq(destinationId), eq(1), isNull(), any(UUID.class));
        // Should not proceed to HTTP call
        verify(attemptRepository, never()).findForwardAttempts(any(), any(), any());
    }

    // -- Retry dispatch: scheduler already claimed, no re-claim --

    @Test
    void retryDispatch_usesAttemptCountDirectly_noReClaim() {
        when(eventRepository.findById(eventId)).thenReturn(Optional.of(buildEvent()));
        when(destinationRepository.findById(destinationId)).thenReturn(Optional.of(buildDestination()));

        IncomingForwardMessage message = IncomingForwardMessage.builder()
                .incomingEventId(eventId).destinationId(destinationId)
                .incomingSourceId(sourceId).attemptCount(2).replay(false)
                .build();

        service.processForward(message);

        // Must NOT call claimForProcessing -- scheduler already did it
        verify(attemptRepository, never()).claimForProcessing(any(), any(), anyInt(), any(), any());
    }

    // -- duplicate Kafka delivery of a retry message must not double-POST --

    @Test
    void retryMessageWithoutFencingToken_legacyProducer_stillDispatches() {
        // Rolling-deploy compatibility: a message published before the startedAt field
        // existed (null) must not be silently dropped -- fall back to the old behavior.
        when(eventRepository.findById(eventId)).thenReturn(Optional.of(buildEvent()));
        when(destinationRepository.findById(destinationId)).thenReturn(Optional.of(buildDestination()));

        IncomingForwardMessage message = IncomingForwardMessage.builder()
                .incomingEventId(eventId).destinationId(destinationId)
                .incomingSourceId(sourceId).attemptCount(2).replay(false)
                .startedAt(null)
                .build();

        service.processForward(message);

        verify(attemptRepository, never()).claimRetryForProcessing(any(), any(), anyInt(), any(), any(), any());
        // Guard chain must have been entered -- proves attemptForward ran.
        verify(concurrencyControlService).tryAcquireForTarget(destinationId);
    }

    @Test
    void duplicateRetryMessage_secondDeliveryFailsClaim_neverEntersDispatch() {
        // Reproduces the duplicate-delivery scenario: IncomingForwardRetryScheduler publishes a retry message,
        // the Kafka offset commit is lost on a rebalance (ordinary at-least-once), the
        // record is re-consumed, and both copies call processForward with an identical
        // message (same fencing token). Without the CAS claim, both would see
        // status=PROCESSING and both would call attemptForward, POSTing twice to the
        // destination. With the CAS, only the delivery that still matches the token
        // proceeds -- the duplicate is rejected before the guard chain (and therefore
        // before the HTTP call) even starts.
        Instant fencingToken = Instant.now();

        when(eventRepository.findById(eventId)).thenReturn(Optional.of(buildEvent()));
        when(destinationRepository.findById(destinationId)).thenReturn(Optional.of(buildDestination()));

        // First delivery wins the CAS; the redelivered duplicate finds the token already
        // consumed (started_at moved on) and updates 0 rows.
        when(attemptRepository.claimRetryForProcessing(eq(eventId), eq(destinationId), eq(2), isNull(), eq(fencingToken), any(UUID.class)))
                .thenReturn(1)
                .thenReturn(0);

        IncomingForwardMessage message = IncomingForwardMessage.builder()
                .incomingEventId(eventId).destinationId(destinationId)
                .incomingSourceId(sourceId).attemptCount(2).replay(false)
                .startedAt(fencingToken)
                .build();

        // Simulate the exact same Kafka record being delivered twice.
        service.processForward(message);
        service.processForward(message);

        verify(attemptRepository, times(2))
                .claimRetryForProcessing(eq(eventId), eq(destinationId), eq(2), isNull(), eq(fencingToken), any(UUID.class));
        // Only the winning delivery must reach the dispatch guard chain -- i.e. exactly
        // one attempt to acquire a concurrency permit, which is what gates the HTTP POST.
        verify(concurrencyControlService, times(1)).tryAcquireForTarget(destinationId);
    }

    // -- SSRF failure: claim + update, not INSERT --

    @Test
    void ssrfFailure_claimsAndUpdatesExistingRow() {
        IncomingDestination dest = buildDestination();
        dest.setUrl("http://169.254.169.254/latest/meta-data");

        when(eventRepository.findById(eventId)).thenReturn(Optional.of(buildEvent()));
        when(destinationRepository.findById(destinationId)).thenReturn(Optional.of(dest));
        when(attemptRepository.claimForProcessing(eq(eventId), eq(destinationId), eq(1), isNull(), any(UUID.class)))
                .thenAnswer(inv -> { claimedToken.set(inv.getArgument(4)); return 1; });

        IncomingForwardAttempt existingAttempt = IncomingForwardAttempt.builder()
                .id(UUID.randomUUID()).incomingEventId(eventId).destinationId(destinationId)
                .attemptNumber(1).status(ForwardAttemptStatus.PROCESSING)
                .build();
        when(attemptRepository.findForwardAttempts(eventId, destinationId, null))
                .thenAnswer(inv -> asClaimed(existingAttempt));

        // Re-create service with allowPrivateIps=false for SSRF to trigger
        IncomingForwardService ssrfService = newService(
                WebClient.builder().build(), new SimpleMeterRegistry(), newAttemptRunner(false));

        IncomingForwardMessage message = IncomingForwardMessage.builder()
                .incomingEventId(eventId).destinationId(destinationId)
                .incomingSourceId(sourceId).attemptCount(0).replay(false)
                .build();

        ssrfService.processForward(message);

        // Must claim via UPDATE then update to FAILED, never INSERT
        verify(attemptRepository).claimForProcessing(eq(eventId), eq(destinationId), eq(1), isNull(), any(UUID.class));
        verify(attemptRepository, never()).saveAndFlush(any(IncomingForwardAttempt.class));

        ArgumentCaptor<IncomingForwardAttempt> captor = ArgumentCaptor.forClass(IncomingForwardAttempt.class);
        verify(attemptRepository).save(captor.capture());
        IncomingForwardAttempt saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(ForwardAttemptStatus.FAILED);
        assertThat(saved.getErrorMessage()).contains("SSRF_PROTECTION");
    }

    // -- Edge cases --

    @Test
    void eventNotFound_skips() {
        when(eventRepository.findById(eventId)).thenReturn(Optional.empty());

        IncomingForwardMessage message = IncomingForwardMessage.builder()
                .incomingEventId(eventId).destinationId(destinationId)
                .incomingSourceId(sourceId).attemptCount(0).replay(false)
                .build();

        service.processForward(message);

        verify(attemptRepository, never()).claimForProcessing(any(), any(), anyInt(), any(), any());
    }

    /**
     * A disabled Destination is decided under the Claim, like every other terminal outcome.
     * This used to be checked here, before anything was claimed, so it wrote FAILED over
     * whatever else owned the row.
     */
    @Test
    void destinationDisabled_failsTheAttemptUnderItsClaim() {
        IncomingDestination dest = buildDestination();
        dest.setEnabled(false);

        when(eventRepository.findById(eventId)).thenReturn(Optional.of(buildEvent()));
        when(destinationRepository.findById(destinationId)).thenReturn(Optional.of(dest));
        when(attemptRepository.claimForProcessing(eq(eventId), eq(destinationId), eq(1), isNull(), any(UUID.class)))
                .thenAnswer(inv -> { claimedToken.set(inv.getArgument(4)); return 1; });

        IncomingForwardAttempt existingAttempt = IncomingForwardAttempt.builder()
                .id(UUID.randomUUID()).incomingEventId(eventId).destinationId(destinationId)
                .attemptNumber(1).status(ForwardAttemptStatus.PROCESSING)
                .build();
        when(attemptRepository.findForwardAttempts(eventId, destinationId, null))
                .thenAnswer(inv -> asClaimed(existingAttempt));

        IncomingForwardMessage message = IncomingForwardMessage.builder()
                .incomingEventId(eventId).destinationId(destinationId)
                .incomingSourceId(sourceId).attemptCount(0).replay(false)
                .build();

        service.processForward(message);

        verify(attemptRepository).claimForProcessing(eq(eventId), eq(destinationId), eq(1), isNull(), any(UUID.class));
        ArgumentCaptor<IncomingForwardAttempt> captor = ArgumentCaptor.forClass(IncomingForwardAttempt.class);
        verify(attemptRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(ForwardAttemptStatus.FAILED);
        assertThat(captor.getValue().getErrorMessage()).isEqualTo("Destination is disabled");
    }

    // -- a configured transformation that fails must fail the attempt, never forward
    // the raw body. --

    @Test
    void configuredTransformationMissing_failsAttemptAsRetryable_doesNotForwardRawBody() {
        IncomingDestination dest = buildDestination();
        UUID transformationId = UUID.randomUUID();
        dest.setTransformationId(transformationId);

        when(eventRepository.findById(eventId)).thenReturn(Optional.of(buildEvent()));
        when(destinationRepository.findById(destinationId)).thenReturn(Optional.of(dest));
        // Simulates the transformation being deleted or disabled after being configured.
        when(transformationCacheService.findEnabledTemplate(transformationId)).thenReturn(null);

        IncomingForwardAttempt existingAttempt = IncomingForwardAttempt.builder()
                .id(UUID.randomUUID()).incomingEventId(eventId).destinationId(destinationId)
                .attemptNumber(2).status(ForwardAttemptStatus.PROCESSING)
                .build();
        when(attemptRepository.findForwardAttempts(eventId, destinationId, null))
                .thenAnswer(inv -> asClaimed(existingAttempt));

        WebClient mockWebClient = mock(WebClient.class);
        MeterRegistry meterRegistry = new SimpleMeterRegistry();
        IncomingForwardService localService = newService(
                mockWebClient, meterRegistry, newAttemptRunner(true));

        // Retry dispatch: scheduler already claimed the row, no re-claim needed.
        IncomingForwardMessage message = IncomingForwardMessage.builder()
                .incomingEventId(eventId).destinationId(destinationId)
                .incomingSourceId(sourceId).attemptCount(2).replay(false)
                .build();

        localService.processForward(message);

        // No HTTP call must have been attempted -- the raw (untransformed) body must never
        // reach the destination.
        verifyNoInteractions(mockWebClient);
        verifyNoInteractions(payloadTransformService);

        ArgumentCaptor<IncomingForwardAttempt> captor = ArgumentCaptor.forClass(IncomingForwardAttempt.class);
        verify(attemptRepository, times(2)).save(captor.capture());
        List<IncomingForwardAttempt> saved = captor.getAllValues();

        // FAILED (not DLQ) because attemptNumber (2) < maxAttempts (5) -- retryable.
        IncomingForwardAttempt failedUpdate = saved.stream()
                .filter(a -> a.getStatus() == ForwardAttemptStatus.FAILED)
                .findFirst().orElseThrow();
        assertThat(failedUpdate.getErrorMessage()).contains("TRANSFORM_FAILED");
        assertThat(failedUpdate.getResponseCode()).isNull();

        // A retry attempt must have been scheduled -- this is a retryable failure, not terminal.
        assertThat(saved.stream().anyMatch(a -> a.getStatus() == ForwardAttemptStatus.PENDING
                && a.getAttemptNumber() == 3)).isTrue();

        assertThat(meterRegistry.get("transform_failed_total").counter().count()).isEqualTo(1.0);
    }

    @Test
    void configuredTransformationMissing_atMaxAttempts_goesToDlq_doesNotForwardRawBody() {
        IncomingDestination dest = buildDestination();
        dest.setMaxAttempts(2);
        UUID transformationId = UUID.randomUUID();
        dest.setTransformationId(transformationId);

        when(eventRepository.findById(eventId)).thenReturn(Optional.of(buildEvent()));
        when(destinationRepository.findById(destinationId)).thenReturn(Optional.of(dest));
        when(transformationCacheService.findEnabledTemplate(transformationId)).thenReturn(null);

        IncomingForwardAttempt existingAttempt = IncomingForwardAttempt.builder()
                .id(UUID.randomUUID()).incomingEventId(eventId).destinationId(destinationId)
                .attemptNumber(2).status(ForwardAttemptStatus.PROCESSING)
                .build();
        when(attemptRepository.findForwardAttempts(eventId, destinationId, null))
                .thenAnswer(inv -> asClaimed(existingAttempt));

        WebClient mockWebClient = mock(WebClient.class);
        IncomingForwardService localService = newService(
                mockWebClient, new SimpleMeterRegistry(), newAttemptRunner(true));

        // attemptCount == maxAttempts -- this is the last attempt.
        IncomingForwardMessage message = IncomingForwardMessage.builder()
                .incomingEventId(eventId).destinationId(destinationId)
                .incomingSourceId(sourceId).attemptCount(2).replay(false)
                .build();

        localService.processForward(message);

        verifyNoInteractions(mockWebClient);

        ArgumentCaptor<IncomingForwardAttempt> captor = ArgumentCaptor.forClass(IncomingForwardAttempt.class);
        verify(attemptRepository).save(captor.capture());
        IncomingForwardAttempt saved = captor.getValue();
        // A permanently broken template must terminate at DLQ, not retry forever.
        assertThat(saved.getStatus()).isEqualTo(ForwardAttemptStatus.DLQ);
        assertThat(saved.getErrorMessage()).contains("Max attempts reached");
    }

    @Test
    void inlineJsonPathTransformFails_failsAttemptAsRetryable_doesNotForwardRawBody() {
        IncomingDestination dest = buildDestination();
        dest.setPayloadTransform("$.this.path.does.not.exist");

        when(eventRepository.findById(eventId)).thenReturn(Optional.of(buildEvent()));
        when(destinationRepository.findById(destinationId)).thenReturn(Optional.of(dest));

        IncomingForwardAttempt existingAttempt = IncomingForwardAttempt.builder()
                .id(UUID.randomUUID()).incomingEventId(eventId).destinationId(destinationId)
                .attemptNumber(1).status(ForwardAttemptStatus.PROCESSING)
                .build();
        when(attemptRepository.findForwardAttempts(eventId, destinationId, null))
                .thenAnswer(inv -> asClaimed(existingAttempt));

        WebClient mockWebClient = mock(WebClient.class);
        when(attemptRepository.claimForProcessing(eq(eventId), eq(destinationId), eq(1), isNull(), any(UUID.class)))
                .thenAnswer(inv -> { claimedToken.set(inv.getArgument(4)); return 1; });
        IncomingForwardService localService = newService(
                mockWebClient, new SimpleMeterRegistry(), newAttemptRunner(true));

        IncomingForwardMessage message = IncomingForwardMessage.builder()
                .incomingEventId(eventId).destinationId(destinationId)
                .incomingSourceId(sourceId).attemptCount(0).replay(false)
                .build();

        localService.processForward(message);

        verifyNoInteractions(mockWebClient);

        ArgumentCaptor<IncomingForwardAttempt> captor = ArgumentCaptor.forClass(IncomingForwardAttempt.class);
        verify(attemptRepository, times(2)).save(captor.capture());
        List<IncomingForwardAttempt> saved = captor.getAllValues();
        IncomingForwardAttempt failedUpdate = saved.stream()
                .filter(a -> a.getStatus() == ForwardAttemptStatus.FAILED)
                .findFirst().orElseThrow();
        assertThat(failedUpdate.getErrorMessage()).contains("TRANSFORM_FAILED");
    }

    /**
     * The finalizers must refuse a row that is no longer PROCESSING. Before the guard, a
     * late writer -- a timed-out call whose 2xx had already landed, or a duplicate Kafka
     * redelivery -- overwrote the terminal row AND created a PENDING successor, forwarding
     * the same event twice.
     */
    @Test
    void updateAttempt_rowAlreadyTerminal_doesNotOverwriteAndDoesNotScheduleSuccessor() {
        IncomingForwardAttempt alreadySucceeded = IncomingForwardAttempt.builder()
                .id(UUID.randomUUID())
                .incomingEventId(eventId).destinationId(destinationId)
                .attemptNumber(1)
                .status(ForwardAttemptStatus.SUCCESS)
                .build();

        when(eventRepository.findById(eventId)).thenReturn(Optional.of(buildEvent()));
        when(destinationRepository.findById(destinationId)).thenReturn(Optional.of(buildDestination()));
        when(attemptRepository.claimForProcessing(eq(eventId), eq(destinationId), eq(1), isNull(), any(UUID.class)))
                .thenAnswer(inv -> { claimedToken.set(inv.getArgument(4)); return 1; });
        when(attemptRepository.findForwardAttempts(eventId, destinationId, null))
                .thenAnswer(inv -> asClaimed(alreadySucceeded));
        when(transformationCacheService.findById(any())).thenReturn(Optional.empty());
        when(payloadTransformService.transform(anyString(), any()))
                .thenThrow(new PayloadTransformException("boom"));

        IncomingForwardMessage message = IncomingForwardMessage.builder()
                .incomingEventId(eventId).destinationId(destinationId)
                .incomingSourceId(sourceId).attemptCount(0).replay(false)
                .build();

        service.processForward(message);

        // Neither the overwrite nor the duplicate successor row.
        verify(attemptRepository, never()).save(any(IncomingForwardAttempt.class));
        assertThat(alreadySucceeded.getStatus()).isEqualTo(ForwardAttemptStatus.SUCCESS);
    }

    /**
     * Backpressure hand-back. next_retry_at must be stamped even for a first-dispatch row
     * that was still PENDING: the scheduler's claim query ignores rows where it is null, so
     * acking without stamping would strand the forward entirely.
     */
    @Test
    void rescheduleForBackpressure_stampsNextRetryAtAndClearsFencingToken() {
        IncomingForwardAttempt inFlight = IncomingForwardAttempt.builder()
                .id(UUID.randomUUID())
                .incomingEventId(eventId).destinationId(destinationId)
                .attemptNumber(1)
                .status(ForwardAttemptStatus.PROCESSING)
                .startedAt(Instant.now())
                .build();

        when(attemptRepository.findForwardAttempts(eventId, destinationId, null))
                .thenAnswer(inv -> asClaimed(inFlight));

        IncomingForwardMessage message = IncomingForwardMessage.builder()
                .incomingEventId(eventId).destinationId(destinationId)
                .incomingSourceId(sourceId).attemptCount(1).replay(false)
                .build();

        service.rescheduleForBackpressure(message);

        ArgumentCaptor<IncomingForwardAttempt> captor = ArgumentCaptor.forClass(IncomingForwardAttempt.class);
        verify(attemptRepository).save(captor.capture());
        IncomingForwardAttempt saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(ForwardAttemptStatus.PENDING);
        assertThat(saved.getNextRetryAt()).isNotNull();
        assertThat(saved.getStartedAt()).isNull();
    }

    @Test
    void rescheduleForBackpressure_rowAlreadyTerminal_isLeftAlone() {
        IncomingForwardAttempt done = IncomingForwardAttempt.builder()
                .id(UUID.randomUUID())
                .incomingEventId(eventId).destinationId(destinationId)
                .attemptNumber(1)
                .status(ForwardAttemptStatus.SUCCESS)
                .build();

        when(attemptRepository.findForwardAttempts(eventId, destinationId, null))
                .thenAnswer(inv -> asClaimed(done));

        service.rescheduleForBackpressure(IncomingForwardMessage.builder()
                .incomingEventId(eventId).destinationId(destinationId)
                .incomingSourceId(sourceId).attemptCount(1).replay(false)
                .build());

        verify(attemptRepository, never()).save(any(IncomingForwardAttempt.class));
    }

    // -- an unusable retry ladder is a terminal configuration failure, not a retry --

    @Test
    void malformedRetryLadder_failsTerminallyWithoutForwarding() {
        // Mirrors WebhookDeliveryServiceTest: retrying cannot fix a ladder that does not
        // parse, and letting RetryLadder throw from calculateNextRetry after the HTTP call
        // would leave the row PROCESSING for StuckForwardRecovery to hand back, forever.
        IncomingDestination destination = buildDestination();
        destination.setRetryDelays("60,oops,900");

        when(eventRepository.findById(eventId)).thenReturn(Optional.of(buildEvent()));
        when(destinationRepository.findById(destinationId)).thenReturn(Optional.of(destination));
        when(attemptRepository.claimForProcessing(eq(eventId), eq(destinationId), eq(1), isNull(), any(UUID.class)))
                .thenAnswer(inv -> { claimedToken.set(inv.getArgument(4)); return 1; });

        IncomingForwardAttempt claimed = IncomingForwardAttempt.builder()
                .incomingEventId(eventId).destinationId(destinationId)
                .attemptNumber(1).status(ForwardAttemptStatus.PROCESSING)
                .build();
        when(attemptRepository.findForwardAttempts(eventId, destinationId, null)).thenAnswer(inv -> asClaimed(claimed));

        IncomingForwardMessage message = IncomingForwardMessage.builder()
                .incomingEventId(eventId).destinationId(destinationId)
                .incomingSourceId(sourceId).attemptCount(0).replay(false)
                .build();

        service.processForward(message);

        ArgumentCaptor<IncomingForwardAttempt> saved = ArgumentCaptor.forClass(IncomingForwardAttempt.class);
        verify(attemptRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus())
                .as("an unusable ladder must terminate the forward")
                .isEqualTo(ForwardAttemptStatus.FAILED);
        assertThat(saved.getValue().getErrorMessage())
                .as("the attempt row must say why")
                .contains("INVALID_RETRY_LADDER");

        // Checked after the claim but before admission: no permit taken, nothing sent.
        verify(concurrencyControlService, never()).tryAcquireForTarget(destinationId);
    }

    // -- a Forward whose ladder is exhausted announces itself --

    @Test
    void ladderExhausted_publishesDlqNotification() {
        // Before this, a DLQ'd Forward wrote its row status and nothing else: incoming.forward.dlq
        // existed and was created by the Makefile, but nothing ever produced a business
        // notification to it.
        IncomingDestination destination = buildDestination();
        destination.setMaxAttempts(1); // exhausted by the first failure

        when(eventRepository.findById(eventId)).thenReturn(Optional.of(buildEvent()));
        when(destinationRepository.findById(destinationId)).thenReturn(Optional.of(destination));
        when(attemptRepository.claimForProcessing(eq(eventId), eq(destinationId), eq(1), isNull(), any(UUID.class)))
                .thenAnswer(inv -> { claimedToken.set(inv.getArgument(4)); return 1; });

        IncomingForwardAttempt claimed = IncomingForwardAttempt.builder()
                .incomingEventId(eventId).destinationId(destinationId)
                .attemptNumber(1).status(ForwardAttemptStatus.PROCESSING)
                .build();
        when(attemptRepository.findForwardAttempts(eventId, destinationId, null)).thenAnswer(inv -> asClaimed(claimed));
        // Nothing is listening on the destination URL, so the attempt errors and, with
        // maxAttempts=1, is abandoned rather than retried.
        destination.setUrl("http://127.0.0.1:1/hook");

        IncomingForwardMessage message = IncomingForwardMessage.builder()
                .incomingEventId(eventId).destinationId(destinationId)
                .incomingSourceId(sourceId).attemptCount(0).replay(false)
                .build();

        service.processForward(message);

        assertThat(claimed.getStatus()).isEqualTo(ForwardAttemptStatus.DLQ);

        ArgumentCaptor<IncomingForwardMessage> published = ArgumentCaptor.forClass(IncomingForwardMessage.class);
        verify(kafkaTemplate).send(eq(KafkaTopics.INCOMING_FORWARD_DLQ), eq(destinationId.toString()),
                published.capture());
        assertThat(published.getValue().getIncomingEventId()).isEqualTo(eventId);
        assertThat(published.getValue().getIncomingSourceId()).isEqualTo(sourceId);
    }

    @Test
    void ladderNotExhausted_publishesNothing() {
        IncomingDestination destination = buildDestination();
        destination.setMaxAttempts(5);

        when(eventRepository.findById(eventId)).thenReturn(Optional.of(buildEvent()));
        when(destinationRepository.findById(destinationId)).thenReturn(Optional.of(destination));
        when(attemptRepository.claimForProcessing(eq(eventId), eq(destinationId), eq(1), isNull(), any(UUID.class)))
                .thenAnswer(inv -> { claimedToken.set(inv.getArgument(4)); return 1; });
        destination.setUrl("http://127.0.0.1:1/hook");

        IncomingForwardAttempt claimed = IncomingForwardAttempt.builder()
                .incomingEventId(eventId).destinationId(destinationId)
                .attemptNumber(1).status(ForwardAttemptStatus.PROCESSING)
                .build();
        when(attemptRepository.findForwardAttempts(eventId, destinationId, null)).thenAnswer(inv -> asClaimed(claimed));

        service.processForward(IncomingForwardMessage.builder()
                .incomingEventId(eventId).destinationId(destinationId)
                .incomingSourceId(sourceId).attemptCount(0).replay(false).build());

        verify(kafkaTemplate, never()).send(any(String.class), any(String.class),
                any(IncomingForwardMessage.class));
    }

    // -- The fence: a reclaimed row belongs to somebody else --

    @Test
    void attemptReclaimedMidFlight_doesNotFinaliseOrQueueASuccessor() {
        when(eventRepository.findById(eventId)).thenReturn(Optional.of(buildEvent()));
        when(destinationRepository.findById(destinationId)).thenReturn(Optional.of(buildDestination()));
        when(attemptRepository.claimForProcessing(eq(eventId), eq(destinationId), eq(1), isNull(), any(UUID.class)))
                .thenAnswer(inv -> { claimedToken.set(inv.getArgument(4)); return 1; });

        // The row as it looks by the time this attempt finishes: PROCESSING again, but under
        // a *different* token — StuckForwardRecoveryService handed it back and the scheduler
        // re-claimed it while this POST was still in flight.
        IncomingForwardAttempt reclaimed = IncomingForwardAttempt.builder()
                .id(UUID.randomUUID()).incomingEventId(eventId).destinationId(destinationId)
                .attemptNumber(1).status(ForwardAttemptStatus.PROCESSING)
                .claimToken(UUID.randomUUID())
                .build();
        when(attemptRepository.findForwardAttempts(eventId, destinationId, null))
                .thenReturn(List.of(reclaimed));

        service.processForward(IncomingForwardMessage.builder()
                .incomingEventId(eventId).destinationId(destinationId)
                .incomingSourceId(sourceId).attemptCount(0).replay(false)
                .build());

        // The status guard alone would have passed here — the row *is* PROCESSING — and this
        // attempt would have written its own outcome over somebody else's claim. Being
        // retryable, it would then have queued attempt 2 as well, so the destination receives
        // the same webhook a third time while the concurrent attempt's result is thrown away.
        assertThat(reclaimed.getStatus())
                .as("a reclaimed row must be left exactly as its current owner left it")
                .isEqualTo(ForwardAttemptStatus.PROCESSING);
        verify(attemptRepository, never()).save(argThat(a ->
                a != null && a.getAttemptNumber() == 2));
    }
}

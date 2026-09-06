package com.webhook.platform.worker.attempt;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import com.webhook.platform.common.dto.DeliveryMessage;
import com.webhook.platform.worker.domain.entity.Delivery;
import com.webhook.platform.worker.domain.repository.DeliveryAttemptRepository;
import com.webhook.platform.worker.domain.repository.DeliveryRepository;
import com.webhook.platform.worker.domain.repository.EndpointRepository;
import com.webhook.platform.worker.domain.repository.EventRepository;
import com.webhook.platform.common.security.EncryptionKeyRegistry;
import com.webhook.platform.worker.service.MtlsWebClientFactory;
import com.webhook.platform.worker.service.OrderingBufferService;
import com.webhook.platform.worker.service.PayloadTransformService;
import com.webhook.platform.worker.service.TransformationCacheService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Only one delivery of a retry message may dispatch.
 *
 * <p>The retry path does not claim PENDING -&gt; PROCESSING — RetrySchedulerService already
 * did that before publishing — so it used to read the row, check the status, and take the
 * fencing token straight out of it. That makes the token worthless as a fence: every copy of
 * the message finds the same value and agrees it owns the row.</p>
 *
 * <p>Two things produce a second copy. Kafka is at-least-once, so a rebalance that loses an
 * offset commit replays the message. And "Send confirmation timeout" in the scheduler hands
 * the row back as PENDING with a null token while the send may still land; the next poll
 * re-claims under a fresh token and publishes again, and the late message then adopted that
 * fresh token and dispatched next to it. Either way two POSTs went out, only the first
 * finalisation applied, and the second webhook left no trace at all.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OutgoingRetryClaimTest {

    @Mock private DeliveryRepository deliveryRepository;
    @Mock private DeliveryAttemptRepository deliveryAttemptRepository;
    @Mock private EndpointRepository endpointRepository;
    @Mock private EventRepository eventRepository;
    @Mock private OrderingBufferService orderingBufferService;
    @Mock private KafkaTemplate<String, DeliveryMessage> kafkaTemplate;
    @Mock private EncryptionKeyRegistry encryptionKeyRegistry;
    @Mock private MtlsWebClientFactory mtlsWebClientFactory;
    @Mock private TransformationCacheService transformationCacheService;
    @Mock private PayloadTransformService payloadTransformService;
    @Mock private TransactionTemplate transactionTemplate;

    private UUID deliveryId;
    private UUID schedulerToken;

    @BeforeEach
    void setUp() {
        deliveryId = UUID.randomUUID();
        schedulerToken = UUID.randomUUID();
        when(transactionTemplate.execute(any())).thenAnswer(invocation ->
                invocation.getArgument(0, TransactionCallback.class).doInTransaction(null));
    }

    private OutgoingAttemptStore retryStoreFor(DeliveryMessage message) {
        return new OutgoingAttemptStore(
                deliveryRepository, deliveryAttemptRepository, endpointRepository, eventRepository,
                transactionTemplate, orderingBufferService, kafkaTemplate, encryptionKeyRegistry,
                mtlsWebClientFactory, transformationCacheService, payloadTransformService,
                new ObjectMapper(), WebClient.builder().build(),
                Counter.builder("test").register(new SimpleMeterRegistry()),
                Clock.systemUTC(), 5, message, true);
    }

    private DeliveryMessage retryMessage(UUID claimToken) {
        return DeliveryMessage.builder()
                .deliveryId(deliveryId)
                .eventId(UUID.randomUUID())
                .endpointId(UUID.randomUUID())
                .subscriptionId(UUID.randomUUID())
                .status(Delivery.DeliveryStatus.PROCESSING.name())
                .attemptCount(2)
                .claimToken(claimToken)
                .build();
    }

    /** The row as the consumer finds it: claimed by the scheduler and still PROCESSING. */
    private Delivery processingRow() {
        return Delivery.builder()
                .id(deliveryId)
                .status(Delivery.DeliveryStatus.PROCESSING)
                .claimToken(UUID.randomUUID())
                .orderingEnabled(false)
                .build();
    }

    @Test
    void aSecondDeliveryOfTheSameRetryMessageClaimsNothing() {
        // The first copy has already won the swap, so the row no longer carries the token
        // this message was published with and the CAS matches nothing.
        when(deliveryRepository.claimRetryForProcessing(eq(deliveryId), eq(schedulerToken), any(UUID.class)))
                .thenReturn(null);

        ClaimResult<OutgoingAttemptStore.Claim> result =
                retryStoreFor(retryMessage(schedulerToken)).claim();

        assertInstanceOf(ClaimResult.NotClaimed.class, result,
                "the loser of the CAS must not go on to POST the webhook a second time");
        // The mechanism, not just the outcome: the claim has to be decided by a conditional
        // swap on the published token. Deriving the fence from the row — which is what
        // findById is for here — is the bug, because every copy of the message finds the
        // same value there and every copy concludes it owns the row.
        verify(deliveryRepository).claimRetryForProcessing(eq(deliveryId), eq(schedulerToken), any(UUID.class));
        verify(deliveryRepository, never()).findById(deliveryId);
    }

    @Test
    void aMessageCarryingAStaleTokenClaimsNothing() {
        // The scheduler timed out waiting for this send, handed the row back, and the next
        // poll re-claimed it under a different token — then this send landed after all.
        UUID staleToken = UUID.randomUUID();
        // The row is PROCESSING under the *new* token. The old code would have read that,
        // adopted it as its fence, and dispatched next to the freshly published message.
        when(deliveryRepository.findById(deliveryId)).thenReturn(Optional.of(processingRow()));
        when(deliveryRepository.claimRetryForProcessing(eq(deliveryId), eq(staleToken), any(UUID.class)))
                .thenReturn(null);

        ClaimResult<OutgoingAttemptStore.Claim> result =
                retryStoreFor(retryMessage(staleToken)).claim();

        assertInstanceOf(ClaimResult.NotClaimed.class, result);
        // Reading the fence from the row is exactly the bug: it would have found the *new*
        // token, agreed the row was PROCESSING, and dispatched alongside the fresh message.
        verify(deliveryRepository, never()).findById(deliveryId);
    }

    @Test
    void aMessageWithoutATokenStillWorksAcrossARollingDeploy() {
        // Published by a worker from before the token travelled with the message. Dropping
        // these would strand every retry already in flight during the upgrade.
        Delivery delivery = Delivery.builder()
                .id(deliveryId)
                .status(Delivery.DeliveryStatus.PROCESSING)
                .claimToken(schedulerToken)
                .orderingEnabled(false)
                .build();
        when(deliveryRepository.findById(deliveryId)).thenReturn(Optional.of(delivery));
        when(endpointRepository.findById(any(UUID.class))).thenReturn(Optional.empty());

        ClaimResult<OutgoingAttemptStore.Claim> result =
                retryStoreFor(retryMessage(null)).claim();

        // It gets past the claim on the old terms; it stops later, at the missing endpoint.
        // atLeastOnce: finalising the terminal outcome re-reads the row under its fence.
        verify(deliveryRepository, atLeastOnce()).findById(deliveryId);
        verify(deliveryRepository, never()).claimRetryForProcessing(any(), any(), any());
        assertInstanceOf(ClaimResult.NotClaimed.class, result);
    }
}

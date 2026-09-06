package com.webhook.platform.worker.attempt;

import com.webhook.platform.common.dto.DeliveryMessage;
import java.time.Clock;
import com.webhook.platform.worker.domain.entity.Delivery;
import com.webhook.platform.worker.domain.repository.DeliveryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutgoingAttemptStoreTest {

    private static final UUID DELIVERY_ID = UUID.randomUUID();
    private static final UUID FENCE = UUID.randomUUID();

    @Mock
    private DeliveryRepository deliveryRepository;

    @Mock
    private TransactionTemplate transactionTemplate;

    private OutgoingAttemptStore store;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        when(transactionTemplate.execute(any())).thenAnswer(inv ->
                inv.getArgument(0, TransactionCallback.class).doInTransaction(null));

        store = new OutgoingAttemptStore(
                deliveryRepository, null, null, null, transactionTemplate,
                null, null, null, null, null, null, null, null, null, Clock.systemUTC(), 5,
                DeliveryMessage.builder().deliveryId(DELIVERY_ID).build(), false);
    }

    @Test
    void successTerminatesTheDelivery() {
        rowIs(processingDelivery());

        assertThat(store.finalise(claim(FENCE), new Finalization.Succeeded())).isTrue();
        assertThat(saved().getStatus()).isEqualTo(Delivery.DeliveryStatus.SUCCESS);
    }

    @Test
    void retryHandsTheRowBackAndReleasesTheClaim() {
        rowIs(processingDelivery());
        Instant at = Instant.now().plusSeconds(60);

        assertThat(store.finalise(claim(FENCE), new Finalization.Retry(at, "500"))).isTrue();

        Delivery row = saved();
        assertThat(row.getStatus()).isEqualTo(Delivery.DeliveryStatus.PENDING);
        assertThat(row.getClaimToken()).isNull();
        assertThat(row.getNextRetryAt()).isEqualTo(at);
    }

    @Test
    void exhaustedLadderGoesToDlq() {
        rowIs(processingDelivery());

        assertThat(store.finalise(claim(FENCE), new Finalization.Abandoned("out of attempts"))).isTrue();
        assertThat(saved().getStatus()).isEqualTo(Delivery.DeliveryStatus.DLQ);
    }

    @Test
    void terminalFailureIsNotRetried() {
        rowIs(processingDelivery());

        assertThat(store.finalise(claim(FENCE), new Finalization.TerminallyFailed("404"))).isTrue();

        Delivery row = saved();
        assertThat(row.getStatus()).isEqualTo(Delivery.DeliveryStatus.FAILED);
        assertThat(row.getNextRetryAt()).isNull();
    }

    @Test
    void aRowThatIsNoLongerProcessingIsNotOverwritten() {
        Delivery row = processingDelivery();
        row.setStatus(Delivery.DeliveryStatus.SUCCESS);
        rowIs(row);

        assertThat(store.finalise(claim(FENCE), new Finalization.Retry(Instant.now(), "500"))).isFalse();
        verify(deliveryRepository, never()).save(any());
    }

    @Test
    void unfencedClaimCannotFinaliseAClaimedRow() {
        rowIs(processingDelivery());

        assertThat(store.finalise(claim(null), new Finalization.Succeeded())).isFalse();
        verify(deliveryRepository, never()).save(any());
    }

    @Test
    void unfencedClaimFinalisesAnUnclaimedRow() {
        Delivery row = processingDelivery();
        row.setClaimToken(null);
        rowIs(row);

        assertThat(store.finalise(claim(null), new Finalization.Succeeded())).isTrue();
    }

    @Test
    void staleFenceCannotFinalise() {
        rowIs(processingDelivery());

        assertThat(store.finalise(claim(UUID.randomUUID()), new Finalization.Succeeded())).isFalse();
        verify(deliveryRepository, never()).save(any());
    }

    @Test
    void aDisappearedRowIsNotFinalised() {
        when(deliveryRepository.findById(DELIVERY_ID)).thenReturn(Optional.empty());

        assertThat(store.finalise(claim(FENCE), new Finalization.Succeeded())).isFalse();
        verify(deliveryRepository, never()).save(any());
    }

    private Delivery processingDelivery() {
        return Delivery.builder()
                .id(DELIVERY_ID)
                .status(Delivery.DeliveryStatus.PROCESSING)
                .attemptCount(1)
                .maxAttempts(5)
                .claimToken(FENCE)
                .build();
    }

    private OutgoingAttemptStore.Claim claim(UUID fence) {
        return new OutgoingAttemptStore.Claim(DELIVERY_ID, fence, processingDelivery());
    }

    private void rowIs(Delivery row) {
        when(deliveryRepository.findById(DELIVERY_ID)).thenReturn(Optional.of(row));
    }

    private Delivery saved() {
        ArgumentCaptor<Delivery> captor = ArgumentCaptor.forClass(Delivery.class);
        verify(deliveryRepository).save(captor.capture());
        return captor.getValue();
    }
}

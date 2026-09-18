package com.webhook.platform.api.domain.entity;

import com.webhook.platform.api.domain.enums.DeliveryStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A Delivery put back on its ladder — a manual retry of a failed or dead-lettered one — must look
 * unclaimed to the worker.
 *
 * <p>The worker leaves its claim token on a Delivery it finished with, and this did not clear it.
 * The replay's dispatch arriving while the outgoing executor was full is rescheduled only when
 * {@code claim_token IS NULL}, so it matched nothing, was acknowledged anyway, and the Delivery sat
 * PENDING with no retry time until the stranded sweep found it an hour later — exactly the
 * Deliveries a customer had just asked to be sent again.
 */
class DeliveryReturnToLadderTest {

    @Test
    @DisplayName("a retried delivery carries no claim left over from its last attempt")
    void clearsTheLeftoverClaim() {
        Delivery delivery = Delivery.builder()
                .status(DeliveryStatus.DLQ)
                .attemptCount(7)
                .maxAttempts(7)
                .claimToken(UUID.randomUUID())
                .build();

        delivery.returnToLadder(Delivery.MANUAL_RETRY_ATTEMPTS);

        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.PENDING);
        assertThat(delivery.getClaimToken()).isNull();
    }
}

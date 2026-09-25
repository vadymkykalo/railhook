package com.webhook.platform.api.domain.entity;

import com.webhook.platform.api.domain.enums.DeliveryStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// The worker's leftover claim token made a manual retry's reschedule match nothing.
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

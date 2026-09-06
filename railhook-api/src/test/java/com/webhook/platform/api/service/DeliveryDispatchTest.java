package com.webhook.platform.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.api.domain.entity.Delivery;
import com.webhook.platform.api.domain.enums.DeliveryStatus;
import com.webhook.platform.api.domain.repository.OutboxMessageRepository;
import com.webhook.platform.common.dto.DeliveryMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class DeliveryDispatchTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private OutboxMessageRepository outboxMessageRepository;

    private final DeliveryDispatch dispatch = new DeliveryDispatch(outboxMessageRepository, MAPPER);

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void everyReasonCarriesTheCorrelationId() {
        MDC.put("correlationId", "corr-1");

        for (DeliveryDispatch.Reason reason : DeliveryDispatch.Reason.values()) {
            assertThat(dispatch.outboxFor(delivery(), UUID.randomUUID(), reason).getCorrelationId())
                    .as("%s must be traceable across both services", reason)
                    .isEqualTo("corr-1");
        }
    }

    @Test
    void theMessageCarriesTheOrderingFieldsOffTheRow() throws Exception {
        Delivery delivery = delivery();
        delivery.setSequenceNumber(7L);
        delivery.setOrderingEnabled(true);

        String payload = dispatch.outboxFor(delivery, UUID.randomUUID(),
                DeliveryDispatch.Reason.REPLAYED).getPayload();

        DeliveryMessage message = MAPPER.readValue(payload, DeliveryMessage.class);
        assertThat(message.getSequenceNumber()).isEqualTo(7L);
        assertThat(message.getOrderingEnabled()).isTrue();
    }

    private Delivery delivery() {
        return Delivery.builder()
                .id(UUID.randomUUID())
                .eventId(UUID.randomUUID())
                .endpointId(UUID.randomUUID())
                .subscriptionId(UUID.randomUUID())
                .status(DeliveryStatus.PENDING)
                .attemptCount(0)
                .build();
    }
}

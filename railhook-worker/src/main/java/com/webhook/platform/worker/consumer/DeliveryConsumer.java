package com.webhook.platform.worker.consumer;

import com.webhook.platform.common.constants.KafkaTopics;
import com.webhook.platform.common.dto.DeliveryMessage;
import com.webhook.platform.worker.service.BoundedAsyncExecutor;
import com.webhook.platform.worker.service.ShutdownRejectedException;
import com.webhook.platform.worker.service.WebhookDeliveryService;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@Slf4j
public class DeliveryConsumer {

    private static final String CORRELATION_ID_KEY = "correlationId";
    private static final String DISPATCH_LISTENER_ID = "deliveryDispatch";
    private static final String RETRY_LISTENER_ID = "deliveryRetry";

    private final WebhookDeliveryService webhookDeliveryService;
    private final BoundedAsyncExecutor asyncExecutor;
    private final BackpressureDispatch backpressureDispatch;
    private final KafkaListenerEndpointRegistry registry;

    public DeliveryConsumer(WebhookDeliveryService webhookDeliveryService,
                            @Qualifier("outgoingDeliveryExecutor") BoundedAsyncExecutor asyncExecutor,
                            KafkaListenerEndpointRegistry registry) {
        this.webhookDeliveryService = webhookDeliveryService;
        this.asyncExecutor = asyncExecutor;
        this.backpressureDispatch = new BackpressureDispatch(asyncExecutor);
        this.registry = registry;
    }

    @EventListener(ApplicationStartedEvent.class)
    void registerContainers() {
        MessageListenerContainer dispatch = registry.getListenerContainer(DISPATCH_LISTENER_ID);
        if (dispatch != null) asyncExecutor.registerContainer(dispatch);
        MessageListenerContainer retry = registry.getListenerContainer(RETRY_LISTENER_ID);
        if (retry != null) asyncExecutor.registerContainer(retry);
    }

    @KafkaListener(
            id = DISPATCH_LISTENER_ID,
            topics = KafkaTopics.DELIVERIES_DISPATCH,
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeDispatch(
            @Payload DeliveryMessage message,
            @Header(KafkaHeaders.RECEIVED_KEY) String key,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(name = "X-Correlation-ID", required = false) byte[] correlationIdBytes,
            Acknowledgment acknowledgment) {
        
        String correlationId = extractCorrelationId(correlationIdBytes);
        MDC.put(CORRELATION_ID_KEY, correlationId);

        log.info("Received delivery from {}: deliveryId={}, endpointId={}",
                topic, message.getDeliveryId(), message.getEndpointId());

        rejectIfShuttingDown(message.getDeliveryId());

        backpressureDispatch.dispatch(
                () -> webhookDeliveryService.processDelivery(message, false),
                acknowledgment,
                "delivery " + message.getDeliveryId(),
                () -> webhookDeliveryService.rescheduleForBackpressure(message.getDeliveryId(), false));
    }

    @KafkaListener(
            id = RETRY_LISTENER_ID,
            topics = {
                    KafkaTopics.DELIVERIES_RETRY_1M,
                    KafkaTopics.DELIVERIES_RETRY_5M,
                    KafkaTopics.DELIVERIES_RETRY_15M,
                    KafkaTopics.DELIVERIES_RETRY_1H,
                    KafkaTopics.DELIVERIES_RETRY_6H,
                    KafkaTopics.DELIVERIES_RETRY_24H
            },
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeRetry(
            @Payload DeliveryMessage message,
            @Header(KafkaHeaders.RECEIVED_KEY) String key,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(name = "X-Correlation-ID", required = false) byte[] correlationIdBytes,
            Acknowledgment acknowledgment) {

        String correlationId = extractCorrelationId(correlationIdBytes);
        MDC.put(CORRELATION_ID_KEY, correlationId);

        log.info("Received retry from {}: deliveryId={}, attempt={}",
                topic, message.getDeliveryId(), message.getAttemptCount());

        rejectIfShuttingDown(message.getDeliveryId());

        backpressureDispatch.dispatch(
                () -> webhookDeliveryService.processDelivery(message, true),
                acknowledgment,
                "retry delivery " + message.getDeliveryId(),
                () -> webhookDeliveryService.rescheduleForBackpressure(message.getDeliveryId(), true));
    }

    /**
     * Checked on the Kafka consumer thread, before the message is ever handed to the
     * async executor. Throwing here — rather than from inside the submitted task — is
     * what lets {@code errorHandler.addNotRetryableExceptions(ShutdownRejectedException.class)}
     * in KafkaConsumerConfig actually see the exception and route the message to the DLQ;
     * a throw from inside the pool thread never reaches the container's error handler.
     */
    private void rejectIfShuttingDown(UUID deliveryId) {
        if (webhookDeliveryService.isShuttingDown()) {
            log.warn("Shutdown in progress, rejecting delivery {} before submission", deliveryId);
            throw new ShutdownRejectedException(
                    "Worker is shutting down, delivery " + deliveryId + " must be redelivered");
        }
    }

    private String extractCorrelationId(byte[] correlationIdBytes) {
        if (correlationIdBytes != null && correlationIdBytes.length > 0) {
            return new String(correlationIdBytes);
        }
        return UUID.randomUUID().toString();
    }
}

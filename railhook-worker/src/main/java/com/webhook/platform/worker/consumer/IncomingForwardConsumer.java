package com.webhook.platform.worker.consumer;

import com.webhook.platform.common.constants.KafkaTopics;
import com.webhook.platform.common.dto.IncomingForwardMessage;
import com.webhook.platform.worker.service.BoundedAsyncExecutor;
import com.webhook.platform.worker.service.IncomingForwardService;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@Slf4j
public class IncomingForwardConsumer {

    private static final String LISTENER_ID = "incomingForward";

    private final IncomingForwardService forwardService;
    private final BoundedAsyncExecutor asyncExecutor;
    private final BackpressureDispatch backpressureDispatch;
    private final KafkaListenerEndpointRegistry registry;

    public IncomingForwardConsumer(IncomingForwardService forwardService,
                                   @Qualifier("incomingForwardExecutor") BoundedAsyncExecutor asyncExecutor,
                                   KafkaListenerEndpointRegistry registry) {
        this.forwardService = forwardService;
        this.asyncExecutor = asyncExecutor;
        this.backpressureDispatch = new BackpressureDispatch(asyncExecutor);
        this.registry = registry;
    }

    @EventListener(ApplicationStartedEvent.class)
    void registerContainers() {
        MessageListenerContainer container = registry.getListenerContainer(LISTENER_ID);
        if (container != null) asyncExecutor.registerContainer(container);
    }

    @KafkaListener(
            id = LISTENER_ID,
            topics = {KafkaTopics.INCOMING_FORWARD_DISPATCH, KafkaTopics.INCOMING_FORWARD_RETRY},
            groupId = "${spring.kafka.consumer.incoming-group-id:incoming-forward-worker}",
            containerFactory = "incomingForwardListenerContainerFactory"
    )
    public void consume(ConsumerRecord<String, IncomingForwardMessage> record, Acknowledgment ack) {
        IncomingForwardMessage message = record.value();
        String correlationId = extractCorrelationId(record);

        MDC.put("correlationId", correlationId);
        MDC.put("incomingEventId", String.valueOf(message.getIncomingEventId()));
        MDC.put("destinationId", String.valueOf(message.getDestinationId()));

        log.info("Received incoming forward message: eventId={}, destId={}, topic={}, replay={}",
                message.getIncomingEventId(), message.getDestinationId(),
                record.topic(), message.isReplay());

        backpressureDispatch.dispatch(
                () -> forwardService.processForward(message),
                ack,
                "forward eventId=" + message.getIncomingEventId(),
                () -> forwardService.rescheduleForBackpressure(message));
    }

    private String extractCorrelationId(ConsumerRecord<String, IncomingForwardMessage> record) {
        org.apache.kafka.common.header.Header header = record.headers().lastHeader("X-Correlation-ID");
        if (header != null && header.value() != null && header.value().length > 0) {
            return new String(header.value(), java.nio.charset.StandardCharsets.UTF_8);
        }
        return UUID.randomUUID().toString();
    }
}

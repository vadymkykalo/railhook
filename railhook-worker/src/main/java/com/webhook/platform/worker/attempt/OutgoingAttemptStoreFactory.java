package com.webhook.platform.worker.attempt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.common.dto.DeliveryMessage;
import com.webhook.platform.common.security.EncryptionKeyRegistry;
import com.webhook.platform.worker.domain.repository.DeliveryAttemptRepository;
import com.webhook.platform.worker.domain.repository.DeliveryRepository;
import com.webhook.platform.worker.domain.repository.EndpointRepository;
import com.webhook.platform.worker.domain.repository.EventRepository;
import com.webhook.platform.worker.service.MtlsWebClientFactory;
import com.webhook.platform.worker.service.OrderingBufferService;
import com.webhook.platform.worker.service.PayloadTransformService;
import com.webhook.platform.worker.service.TransformationCacheService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Clock;

/**
 * Holds everything an {@link OutgoingAttemptStore} needs, so a caller supplies only the message.
 *
 * <p>A store is one per Attempt and thread-confined: it holds the loaded Endpoint and Event. That
 * used to be a sentence in a javadoc with a sixteen-argument constructor behind it, fourteen of
 * whose arguments the calling service held as fields solely to pass along.
 */
@Component
public class OutgoingAttemptStoreFactory {

    private final DeliveryRepository deliveryRepository;
    private final DeliveryAttemptRepository deliveryAttemptRepository;
    private final EndpointRepository endpointRepository;
    private final EventRepository eventRepository;
    private final TransactionTemplate transactionTemplate;
    private final OrderingBufferService orderingBufferService;
    private final KafkaTemplate<String, DeliveryMessage> kafkaTemplate;
    private final EncryptionKeyRegistry encryptionKeyRegistry;
    private final MtlsWebClientFactory mtlsWebClientFactory;
    private final TransformationCacheService transformationCacheService;
    private final PayloadTransformService payloadTransformService;
    private final ObjectMapper objectMapper;
    private final WebClient outgoingWebClient;
    private final Counter orderingGapTimeoutCounter;
    private final Clock clock;

    /**
     * How long a Delivery blocked behind an outstanding sequence waits before it is re-polled.
     * The fallback path only: the fast path republishes buffered Deliveries the moment the
     * sequence ahead of them completes, but that chain is broken for a Delivery that reaches the
     * buffer after its predecessor's trigger already fired, and nothing but this poll restarts it.
     */
    private final int orderingBufferRescheduleDelaySeconds;

    public OutgoingAttemptStoreFactory(
            DeliveryRepository deliveryRepository,
            DeliveryAttemptRepository deliveryAttemptRepository,
            EndpointRepository endpointRepository,
            EventRepository eventRepository,
            TransactionTemplate transactionTemplate,
            OrderingBufferService orderingBufferService,
            KafkaTemplate<String, DeliveryMessage> kafkaTemplate,
            EncryptionKeyRegistry encryptionKeyRegistry,
            MtlsWebClientFactory mtlsWebClientFactory,
            TransformationCacheService transformationCacheService,
            PayloadTransformService payloadTransformService,
            ObjectMapper objectMapper,
            @Qualifier("outgoingWebClient") WebClient outgoingWebClient,
            MeterRegistry meterRegistry,
            Clock clock,
            @Value("${ordering.buffer-reschedule-delay-seconds:5}") int orderingBufferRescheduleDelaySeconds) {
        this.deliveryRepository = deliveryRepository;
        this.deliveryAttemptRepository = deliveryAttemptRepository;
        this.endpointRepository = endpointRepository;
        this.eventRepository = eventRepository;
        this.transactionTemplate = transactionTemplate;
        this.orderingBufferService = orderingBufferService;
        this.kafkaTemplate = kafkaTemplate;
        this.encryptionKeyRegistry = encryptionKeyRegistry;
        this.mtlsWebClientFactory = mtlsWebClientFactory;
        this.transformationCacheService = transformationCacheService;
        this.payloadTransformService = payloadTransformService;
        this.objectMapper = objectMapper;
        this.outgoingWebClient = outgoingWebClient;
        this.orderingGapTimeoutCounter = Counter.builder("webhook_ordering_gap_timeout_total")
                .register(meterRegistry);
        this.clock = clock;
        this.orderingBufferRescheduleDelaySeconds = orderingBufferRescheduleDelaySeconds;
    }

    public OutgoingAttemptStore create(DeliveryMessage message, boolean isRetry) {
        return new OutgoingAttemptStore(
                deliveryRepository, deliveryAttemptRepository, endpointRepository, eventRepository,
                transactionTemplate, orderingBufferService, kafkaTemplate, encryptionKeyRegistry,
                mtlsWebClientFactory, transformationCacheService, payloadTransformService,
                objectMapper, outgoingWebClient, orderingGapTimeoutCounter, clock,
                orderingBufferRescheduleDelaySeconds, message, isRetry);
    }
}

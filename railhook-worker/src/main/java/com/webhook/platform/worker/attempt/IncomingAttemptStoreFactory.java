package com.webhook.platform.worker.attempt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.common.dto.IncomingForwardMessage;
import com.webhook.platform.common.security.EncryptionKeyRegistry;
import com.webhook.platform.worker.domain.entity.IncomingDestination;
import com.webhook.platform.worker.domain.entity.IncomingEvent;
import com.webhook.platform.worker.domain.repository.IncomingForwardAttemptRepository;
import com.webhook.platform.worker.service.PayloadTransformService;
import com.webhook.platform.worker.service.TransformationCacheService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.reactive.function.client.WebClient;

/** Holds everything an {@link IncomingAttemptStore} needs, so a caller supplies only the Forward. */
@Component
public class IncomingAttemptStoreFactory {

    private final IncomingForwardAttemptRepository attemptRepository;
    private final TransactionTemplate transactionTemplate;
    private final TransformationCacheService transformationCacheService;
    private final PayloadTransformService payloadTransformService;
    private final EncryptionKeyRegistry encryptionKeyRegistry;
    private final ObjectMapper objectMapper;
    private final WebClient incomingForwardWebClient;
    private final KafkaTemplate<String, IncomingForwardMessage> kafkaTemplate;

    public IncomingAttemptStoreFactory(
            IncomingForwardAttemptRepository attemptRepository,
            TransactionTemplate transactionTemplate,
            TransformationCacheService transformationCacheService,
            PayloadTransformService payloadTransformService,
            EncryptionKeyRegistry encryptionKeyRegistry,
            ObjectMapper objectMapper,
            @Qualifier("incomingForwardWebClient") WebClient incomingForwardWebClient,
            @Qualifier("incomingForwardKafkaTemplate")
            KafkaTemplate<String, IncomingForwardMessage> kafkaTemplate) {
        this.attemptRepository = attemptRepository;
        this.transactionTemplate = transactionTemplate;
        this.transformationCacheService = transformationCacheService;
        this.payloadTransformService = payloadTransformService;
        this.encryptionKeyRegistry = encryptionKeyRegistry;
        this.objectMapper = objectMapper;
        this.incomingForwardWebClient = incomingForwardWebClient;
        this.kafkaTemplate = kafkaTemplate;
    }

    public IncomingAttemptStore create(IncomingForwardMessage message, IncomingEvent event,
            IncomingDestination destination) {
        return new IncomingAttemptStore(
                attemptRepository, transactionTemplate, transformationCacheService,
                payloadTransformService, encryptionKeyRegistry, objectMapper,
                incomingForwardWebClient, kafkaTemplate, message, event, destination);
    }
}

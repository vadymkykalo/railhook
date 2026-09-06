package com.webhook.platform.worker.attempt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webhook.platform.common.enums.IncomingAuthType;
import com.webhook.platform.common.security.EncryptionKeyRegistry;
import com.webhook.platform.worker.domain.entity.IncomingDestination;
import lombok.extern.slf4j.Slf4j;

import java.util.Base64;
import java.util.Map;

/**
 * Authenticates a Forward to its Destination. The Outgoing counterpart is {@link DeliverySigner}:
 * Railhook signs what it sends and proves who it is when relaying onward.
 */
@Slf4j
class DestinationAuthenticator {

    private final IncomingDestination destination;
    private final EncryptionKeyRegistry encryptionKeyRegistry;
    private final ObjectMapper objectMapper;

    DestinationAuthenticator(IncomingDestination destination, EncryptionKeyRegistry encryptionKeyRegistry,
            ObjectMapper objectMapper) {
        this.destination = destination;
        this.encryptionKeyRegistry = encryptionKeyRegistry;
        this.objectMapper = objectMapper;
    }

    /**
     * Puts the credentials into the header map rather than straight onto the request: the caller
     * has to record what it sent, and a header applied to the request builder cannot be read back.
     */
    @SuppressWarnings("unchecked")
    void authenticate(Map<String, String> headers) {
        if (destination.getAuthType() == IncomingAuthType.NONE || destination.getAuthConfigEncrypted() == null) {
            return;
        }
        try {
            String authConfig = encryptionKeyRegistry.decryptWithFallback(
                    destination.getAuthConfigEncrypted(),
                    destination.getAuthConfigIv(),
                    destination.getEncryptionKeyVersion());
            Map<String, String> config = objectMapper.readValue(authConfig, Map.class);

            switch (destination.getAuthType()) {
                case BEARER -> {
                    String token = config.get("token");
                    if (token != null) {
                        headers.put("Authorization", "Bearer " + token);
                    }
                }
                case BASIC -> {
                    String username = config.getOrDefault("username", "");
                    String password = config.getOrDefault("password", "");
                    headers.put("Authorization", "Basic " + Base64.getEncoder()
                            .encodeToString((username + ":" + password).getBytes()));
                }
                case CUSTOM_HEADER -> {
                    String name = config.get("headerName");
                    String value = config.get("headerValue");
                    if (name != null && value != null) {
                        headers.put(name, value);
                    }
                }
                default -> {
                }
            }
        } catch (Exception e) {
            log.warn("Failed to apply auth headers for destination {}: {}", destination.getId(), e.getMessage());
        }
    }
}

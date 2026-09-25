package com.webhook.platform.api.security;

import java.util.UUID;

/** No organization here: the tenant filter has already confined every query to it. */
public record PortalContext(UUID sessionId, UUID projectId, UUID consumerId) {
}

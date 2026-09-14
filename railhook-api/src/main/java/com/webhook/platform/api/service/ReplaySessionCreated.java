package com.webhook.platform.api.service;

import java.util.UUID;

/** Published inside the transaction that creates a replay session; acted on once it commits. */
public record ReplaySessionCreated(UUID sessionId) {
}

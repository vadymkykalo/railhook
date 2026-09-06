package com.webhook.platform.common.constants;

public final class KafkaTopics {
    public static final String DELIVERIES_DISPATCH = "deliveries.dispatch";

    // These 6 names are labels, not delay mechanisms. Kafka does not delay delivery of
    // a message to a consumer just because the topic name says "1h" — DeliveryConsumer listens
    // to all six and processes whatever it receives immediately. The actual retry delay is
    // enforced entirely by next_retry_at on the `deliveries` row: RetrySchedulerService only
    // claims (findPendingRetryIds) deliveries whose next_retry_at has passed, and it picks
    // *which* of these topics to publish to purely by attemptCount, as a routing label. Do
    // not "fix" the scheduler to add a matching delay here — that would double the wait.
    public static final String DELIVERIES_RETRY_1M = "deliveries.retry.1m";
    public static final String DELIVERIES_RETRY_5M = "deliveries.retry.5m";
    public static final String DELIVERIES_RETRY_15M = "deliveries.retry.15m";
    public static final String DELIVERIES_RETRY_1H = "deliveries.retry.1h";
    public static final String DELIVERIES_RETRY_6H = "deliveries.retry.6h";
    public static final String DELIVERIES_RETRY_24H = "deliveries.retry.24h";
    public static final String DELIVERIES_DLQ = "deliveries.dlq";

    // Incoming webhooks forwarding
    public static final String INCOMING_FORWARD_DISPATCH = "incoming.forward.dispatch";
    public static final String INCOMING_FORWARD_RETRY = "incoming.forward.retry";
    public static final String INCOMING_FORWARD_DLQ = "incoming.forward.dlq";

    private KafkaTopics() {
    }
}

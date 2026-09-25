package com.webhook.platform.common.constants;

public final class KafkaTopics {
    public static final String DELIVERIES_DISPATCH = "deliveries.dispatch";

    // These names are routing labels, not delays. The consumer handles all six immediately; the
    // delay is next_retry_at on the row. Adding a delay here would double the wait.
    public static final String DELIVERIES_RETRY_1M = "deliveries.retry.1m";
    public static final String DELIVERIES_RETRY_5M = "deliveries.retry.5m";
    public static final String DELIVERIES_RETRY_15M = "deliveries.retry.15m";
    public static final String DELIVERIES_RETRY_1H = "deliveries.retry.1h";
    public static final String DELIVERIES_RETRY_6H = "deliveries.retry.6h";
    public static final String DELIVERIES_RETRY_24H = "deliveries.retry.24h";
    public static final String DELIVERIES_DLQ = "deliveries.dlq";

    public static final String INCOMING_FORWARD_DISPATCH = "incoming.forward.dispatch";
    public static final String INCOMING_FORWARD_RETRY = "incoming.forward.retry";
    public static final String INCOMING_FORWARD_DLQ = "incoming.forward.dlq";

    private KafkaTopics() {
    }
}

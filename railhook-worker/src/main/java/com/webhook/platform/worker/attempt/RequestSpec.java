package com.webhook.platform.worker.attempt;

import org.springframework.web.reactive.function.client.WebClient;

import java.util.function.Consumer;

/**
 * The request minus its body, which the Runner supplies. {@code recordedHeaders} is shown in the
 * dashboard, so secrets and signatures must already be masked.
 */
public record RequestSpec(
        WebClient client,
        Consumer<WebClient.RequestBodySpec> headers,
        String recordedHeaders) {
}

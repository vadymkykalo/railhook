package com.webhook.platform.worker.attempt;

import org.springframework.web.reactive.function.client.WebClient;

import java.util.function.Consumer;

/**
 * The HTTP request an {@link AttemptStore} wants made, minus the body — the Runner supplies
 * that, because it owns the transformation and the rule that a failed transformation must
 * never let the raw payload leave the platform.
 *
 * <p>Everything else genuinely differs between the directions and stays in the adapter:
 * Outgoing computes an HMAC signature from the Endpoint's secret and may need an mTLS
 * client; Incoming attaches the Destination's own auth credentials and passes the original
 * content type through. Neither has any business being a conditional inside the Runner.
 *
 * @param client         the WebClient to send with — the shared SSRF-safe one, or a
 *                       per-target mTLS one
 * @param headers        applied to the request before the body is set
 * @param recordedHeaders the request headers as they should be stored on the attempt record,
 *                        already sanitised: secrets and signatures must be masked here,
 *                        because this string is shown in the dashboard
 */
public record RequestSpec(
        WebClient client,
        Consumer<WebClient.RequestBodySpec> headers,
        String recordedHeaders) {
}

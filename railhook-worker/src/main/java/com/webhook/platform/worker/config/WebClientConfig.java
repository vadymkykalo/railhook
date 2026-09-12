package com.webhook.platform.worker.config;

import com.webhook.platform.common.http.SsrfProtectionCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.webclient.WebClientCustomizer;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

@Configuration
public class WebClientConfig {

    /**
     * How much of a response body to buffer before giving up on reading it.
     *
     * <p>Declared rather than inherited. Spring's default is 256 KiB, and a receiver that
     * answers 2xx with more than that used to turn a delivered webhook into a failed one —
     * {@code AttemptRunner} no longer lets a read decide an Attempt, but the limit still
     * belongs somewhere a person can see it. There is no reason to buffer much: both stores
     * truncate the body to 10 KiB before it reaches the database.
     *
     * <p>Applied as a {@link WebClientCustomizer} so it reaches every injected
     * {@code WebClient.Builder} — the two clients below and {@code MtlsWebClientFactory}'s,
     * which builds its own and would otherwise keep the default.
     */
    @Bean
    public WebClientCustomizer responseBodyBufferLimit(
            @Value("${webhook.max-response-body-bytes:1048576}") int maxResponseBodyBytes) {
        return builder -> builder.codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(maxResponseBodyBytes));
    }

    @Bean
    public ConnectionProvider webhookConnectionProvider(
            @Value("${webhook.connection-pool.max-connections:200}") int maxConnections,
            @Value("${webhook.connection-pool.pending-acquire-timeout-seconds:10}") int pendingAcquireTimeoutSeconds,
            @Value("${webhook.connection-pool.max-idle-time-seconds:60}") int maxIdleTimeSeconds) {
        return SsrfProtectionCustomizer.createConnectionProvider(
                maxConnections, pendingAcquireTimeoutSeconds, maxIdleTimeSeconds);
    }

    /**
     * The client an Outgoing Delivery goes out on. SSRF validation happens after the TCP connect,
     * against the address actually resolved, so a DNS answer that changes between validation and
     * request cannot get through.
     */
    @Bean
    public WebClient outgoingWebClient(WebClient.Builder builder, ConnectionProvider webhookConnectionProvider,
            @Value("${webhook.url-validation.allow-private-ips:false}") boolean allowPrivateIps,
            @Value("${webhook.url-validation.allowed-hosts:}") java.util.List<String> allowedHosts) {
        return ssrfSafe(builder, webhookConnectionProvider, allowPrivateIps, allowedHosts)
                .defaultHeader("User-Agent", "WebhookPlatform/1.0")
                .build();
    }

    /** The same client for the Incoming direction, which sends no User-Agent of its own. */
    @Bean
    public WebClient incomingForwardWebClient(WebClient.Builder builder,
            ConnectionProvider webhookConnectionProvider,
            @Value("${webhook.url-validation.allow-private-ips:false}") boolean allowPrivateIps,
            @Value("${webhook.url-validation.allowed-hosts:}") java.util.List<String> allowedHosts) {
        return ssrfSafe(builder, webhookConnectionProvider, allowPrivateIps, allowedHosts).build();
    }

    private WebClient.Builder ssrfSafe(WebClient.Builder builder, ConnectionProvider connectionProvider,
            boolean allowPrivateIps, java.util.List<String> allowedHosts) {
        HttpClient httpClient = SsrfProtectionCustomizer.createHttpClient(
                connectionProvider, allowPrivateIps, allowedHosts);
        return builder.clientConnector(new ReactorClientHttpConnector(httpClient));
    }
}

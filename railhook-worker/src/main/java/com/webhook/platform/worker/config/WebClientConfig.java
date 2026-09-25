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
import java.util.List;

@Configuration
public class WebClientConfig {

    /**
     * Spring's 256 KiB default once turned a large 2xx answer into a failed delivery. Both stores
     * truncate the body to 10 KiB anyway. A customizer, so it also reaches the builder
     * {@code MtlsWebClientFactory} uses.
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

    // SSRF validation runs after connect, against the resolved address, so a DNS answer that
    // changes between validation and request cannot get through.
    @Bean
    public WebClient outgoingWebClient(WebClient.Builder builder, ConnectionProvider webhookConnectionProvider,
            @Value("${webhook.url-validation.allow-private-ips:false}") boolean allowPrivateIps,
            @Value("${webhook.url-validation.allowed-hosts:}") List<String> allowedHosts) {
        return ssrfSafe(builder, webhookConnectionProvider, allowPrivateIps, allowedHosts)
                .defaultHeader("User-Agent", "WebhookPlatform/1.0")
                .build();
    }

    @Bean
    public WebClient incomingForwardWebClient(WebClient.Builder builder,
            ConnectionProvider webhookConnectionProvider,
            @Value("${webhook.url-validation.allow-private-ips:false}") boolean allowPrivateIps,
            @Value("${webhook.url-validation.allowed-hosts:}") List<String> allowedHosts) {
        return ssrfSafe(builder, webhookConnectionProvider, allowPrivateIps, allowedHosts).build();
    }

    private WebClient.Builder ssrfSafe(WebClient.Builder builder, ConnectionProvider connectionProvider,
            boolean allowPrivateIps, List<String> allowedHosts) {
        HttpClient httpClient = SsrfProtectionCustomizer.createHttpClient(
                connectionProvider, allowPrivateIps, allowedHosts);
        return builder.clientConnector(new ReactorClientHttpConnector(httpClient));
    }
}

package com.webhook.platform.common.http;

import com.webhook.platform.common.security.UrlValidator;
import io.netty.channel.ChannelOption;
import lombok.extern.slf4j.Slf4j;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;

/**
 * Re-checks the connected peer's address after TCP connect, closing the DNS rebinding window
 * left by validating the name first.
 *
 * <p>Reactor Netty is {@code provided}: api and worker get it through webflux, and the CLI binary
 * should not carry netty for a class it never calls.
 */
@Slf4j
public final class SsrfProtectionCustomizer {

    private SsrfProtectionCustomizer() {
    }

    /** {@code metrics(true)} makes Reactor Netty register the pool gauges with Micrometer. */
    public static ConnectionProvider createConnectionProvider(
            int maxConnections, int pendingAcquireTimeoutSeconds, int maxIdleTimeSeconds) {
        log.info("Creating webhook connection pool: maxConnections={}, pendingAcquireTimeout={}s, maxIdleTime={}s",
                maxConnections, pendingAcquireTimeoutSeconds, maxIdleTimeSeconds);
        return ConnectionProvider.builder("webhook-pool")
                .maxConnections(maxConnections)
                .pendingAcquireTimeout(Duration.ofSeconds(pendingAcquireTimeoutSeconds))
                .maxIdleTime(Duration.ofSeconds(maxIdleTimeSeconds))
                .metrics(true)
                .build();
    }

    public static HttpClient createHttpClient(ConnectionProvider connectionProvider,
                                              boolean allowPrivateIps, List<String> allowedHosts) {
        return apply(HttpClient.create(connectionProvider), allowPrivateIps, allowedHosts);
    }

    /**
     * @param allowedHosts the same list admission validated against. Without it an allow-listed
     *                     internal host passes admission and then fails every connection here.
     */
    public static HttpClient apply(HttpClient httpClient, boolean allowPrivateIps, List<String> allowedHosts) {
        httpClient = httpClient
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5_000);

        // ProductionSafetyValidator refuses this switch in production.
        if (allowPrivateIps) {
            return httpClient;
        }

        return httpClient.doOnConnected(conn -> {
            var remoteAddress = conn.channel().remoteAddress();
            if (remoteAddress instanceof InetSocketAddress isa) {
                InetAddress addr = isa.getAddress();
                // getHostString is the name the client was given, which is what the allow list
                // holds. A bare literal will not match a name entry, so this fails closed.
                if (addr != null
                        && UrlValidator.isBlockedTarget(isa.getHostString(), addr, allowPrivateIps, allowedHosts)) {
                    log.warn("SSRF protection: DNS rebinding detected, resolved to private IP {}", addr.getHostAddress());
                    conn.dispose();
                    throw new UrlValidator.InvalidUrlException(
                            "SSRF protection: connection resolved to private IP " + addr.getHostAddress());
                }
            }
        });
    }
}

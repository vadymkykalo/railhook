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
 * Applies post-connect SSRF protection to Reactor Netty HttpClient.
 * Validates the actual resolved IP after TCP connection, closing the TOCTOU
 * window between DNS validation and HTTP request (DNS rebinding mitigation).
 *
 * <p>Lives here, next to {@link UrlValidator} — the thing it validates against — rather than
 * once in api and once in worker. It was byte-identical in both apart from the package line,
 * so an SSRF fix had to be applied twice and nothing said so.
 *
 * <p>Reactor Netty is {@code provided} on purpose: both modules that use this already have it
 * through webflux, and making it {@code compile} would put netty into the CLI binary for a class
 * the CLI never calls. A consumer without webflux gets a NoClassDefFoundError at runtime.
 */
@Slf4j
public final class SsrfProtectionCustomizer {

    private SsrfProtectionCustomizer() {
    }

    /**
     * Creates a configurable ConnectionProvider with metrics enabled.
     * Reactor Netty auto-registers Micrometer gauges when metrics(true):
     *   reactor.netty.connection.provider.{pool-name}.pending-connections
     *   reactor.netty.connection.provider.{pool-name}.active-connections
     *   reactor.netty.connection.provider.{pool-name}.idle-connections
     *   reactor.netty.connection.provider.{pool-name}.total-connections
     */
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

    /**
     * Creates a new HttpClient with the given connection provider, connect timeout,
     * and SSRF protection.
     */
    public static HttpClient createHttpClient(ConnectionProvider connectionProvider,
                                              boolean allowPrivateIps, List<String> allowedHosts) {
        return apply(HttpClient.create(connectionProvider), allowPrivateIps, allowedHosts);
    }

    /**
     * @param allowedHosts the same list {@code UrlValidator.validateWebhookUrl} was given at
     *                     admission. Passing it matters: without it this check reaches a
     *                     different verdict than admission did, and an operator who
     *                     allow-listed an internal host watches every delivery to it die at
     *                     the TCP layer with nothing in the configuration to explain it.
     */
    public static HttpClient apply(HttpClient httpClient, boolean allowPrivateIps, List<String> allowedHosts) {
        httpClient = httpClient
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5_000);

        // allow-private-ips is the operator switching the protection off outright, and
        // ProductionSafetyValidator refuses to start with it on in production. Unchanged.
        if (allowPrivateIps) {
            return httpClient;
        }

        return httpClient.doOnConnected(conn -> {
            var remoteAddress = conn.channel().remoteAddress();
            if (remoteAddress instanceof InetSocketAddress isa) {
                InetAddress addr = isa.getAddress();
                // getHostString gives the name the client was pointed at when there is one,
                // which is what the allow list holds; it falls back to the literal, and a
                // literal simply will not match a name entry - fail closed, as before.
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

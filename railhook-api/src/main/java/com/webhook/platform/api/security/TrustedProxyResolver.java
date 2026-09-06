package com.webhook.platform.api.security;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Resolves the real client IP for a request, honouring {@code X-Forwarded-For} /
 * {@code X-Real-IP} only when the direct TCP peer is a configured trusted proxy.
 *
 * <p>This is the single shared resolver for the whole API module — ingress webhook
 * handling, auth rate limiting, audit logging and the test-endpoint feature all
 * route through this class rather than each re-implementing header parsing.
 *
 * <p>Default is safe: with no {@code webhook.trusted-proxies} configured, nothing is
 * trusted and every caller always gets {@link HttpServletRequest#getRemoteAddr()}.
 * An operator running behind a reverse proxy / load balancer must explicitly list
 * that proxy's address (or CIDR) to enable header-based resolution.
 */
@Component
@Slf4j
public class TrustedProxyResolver {

    /**
     * A hop value is only handed to {@link InetAddress#getByName(String)} (which can
     * fall back to a DNS lookup for non-literal input) once it has passed this literal
     * IPv4/IPv6 syntax check. X-Forwarded-For hops come from the request when the peer
     * is trusted, but individual hop values inside the header are still attacker
     * reachable via a compromised or overly-broad proxy chain, so we never let one
     * trigger a network DNS query.
     */
    private static final Pattern IPV4_LITERAL = Pattern.compile(
            "^((25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1?\\d?\\d)$");
    private static final Pattern IPV6_CHARSET = Pattern.compile("^[0-9a-fA-F:.]+$");

    private final List<String> trustedProxies;

    public TrustedProxyResolver(
            @Value("${webhook.trusted-proxies:}") List<String> trustedProxies) {
        this.trustedProxies = trustedProxies;
    }

    /**
     * Resolves the client IP for the given request.
     *
     * <p>If the direct peer ({@code getRemoteAddr()}) is not a trusted proxy, the
     * peer address is returned as-is and no header is consulted.
     *
     * <p>If the peer is trusted, {@code X-Forwarded-For} is walked from the right
     * (nearest hop first); the first hop that is not itself a trusted proxy is
     * returned as the client IP. This is the reverse of naively taking the
     * left-most entry, which is always attacker-controlled input on any chain
     * with a trusted proxy in it. If every hop is trusted (a fully internal
     * chain), the left-most (original) entry is returned. Falls back to
     * {@code X-Real-IP}, then to the peer address, if no header is present.
     */
    public String resolve(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();
        if (!isTrustedProxy(remoteAddr)) {
            return remoteAddr;
        }

        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            String[] hops = xForwardedFor.split(",");
            for (int i = hops.length - 1; i >= 0; i--) {
                String hop = hops[i].trim();
                if (hop.isEmpty()) {
                    continue;
                }
                if (!isTrustedProxy(hop)) {
                    return hop;
                }
            }
            // Every hop in the chain is itself a trusted proxy (fully internal
            // hop chain) — fall back to the original, left-most entry.
            for (String hop : hops) {
                String trimmed = hop.trim();
                if (!trimmed.isEmpty()) {
                    return trimmed;
                }
            }
        }

        String xRealIp = request.getHeader("X-Real-IP");
        if (xRealIp != null && !xRealIp.isBlank()) {
            return xRealIp.trim();
        }
        return remoteAddr;
    }

    boolean isTrustedProxy(String address) {
        if (trustedProxies == null || trustedProxies.isEmpty()) {
            return false;
        }
        if (address == null || !isLiteralIpAddress(address)) {
            return false;
        }
        try {
            InetAddress remote = InetAddress.getByName(address);
            byte[] remoteBytes = remote.getAddress();
            for (String proxy : trustedProxies) {
                String trimmed = proxy.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                if (trimmed.contains("/")) {
                    String[] parts = trimmed.split("/");
                    InetAddress network = InetAddress.getByName(parts[0]);
                    int prefixLen = Integer.parseInt(parts[1]);
                    if (isInCidr(remoteBytes, network.getAddress(), prefixLen)) {
                        return true;
                    }
                } else {
                    InetAddress trusted = InetAddress.getByName(trimmed);
                    if (remote.equals(trusted)) {
                        return true;
                    }
                }
            }
        } catch (UnknownHostException e) {
            log.warn("Failed to resolve address for trusted proxy check: {}", address);
        }
        return false;
    }

    /**
     * True only for literal IPv4/IPv6 syntax — never for hostnames. Guards every
     * call site that feeds attacker-influenced strings into {@code InetAddress},
     * which otherwise silently falls back to a real DNS lookup for anything that
     * isn't a recognised literal address.
     */
    static boolean isLiteralIpAddress(String address) {
        if (IPV4_LITERAL.matcher(address).matches()) {
            return true;
        }
        return address.indexOf(':') >= 0 && IPV6_CHARSET.matcher(address).matches();
    }

    static boolean isInCidr(byte[] addr, byte[] network, int prefixLen) {
        if (addr.length != network.length) return false;
        int fullBytes = prefixLen / 8;
        int remainingBits = prefixLen % 8;
        for (int i = 0; i < fullBytes; i++) {
            if (addr[i] != network[i]) return false;
        }
        if (remainingBits > 0 && fullBytes < addr.length) {
            int mask = (0xFF << (8 - remainingBits)) & 0xFF;
            if ((addr[fullBytes] & mask) != (network[fullBytes] & mask)) return false;
        }
        return true;
    }
}

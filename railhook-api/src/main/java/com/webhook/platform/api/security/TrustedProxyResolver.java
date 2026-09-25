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
 * Forwarding headers are honoured only when the direct peer is a configured trusted proxy.
 * With {@code webhook.trusted-proxies} unset nothing is trusted and the peer address is used.
 */
@Component
@Slf4j
public class TrustedProxyResolver {

    // Header hops are attacker-controlled; only literal IPs reach InetAddress, which would
    // otherwise do a DNS lookup.
    private static final Pattern IPV4_LITERAL = Pattern.compile(
            "^((25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1?\\d?\\d)$");
    private static final Pattern IPV6_CHARSET = Pattern.compile("^[0-9a-fA-F:.]+$");

    private final List<String> trustedProxies;

    public TrustedProxyResolver(
            @Value("${webhook.trusted-proxies:}") List<String> trustedProxies) {
        this.trustedProxies = trustedProxies;
    }

    /**
     * X-Forwarded-For is walked from the right and the first untrusted hop wins: the left-most
     * entry is whatever the client chose to send.
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
            // Fully internal chain: every hop is trusted.
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
